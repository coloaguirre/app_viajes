package com.bitacoraviaje.app.ui.tripdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.map.MapMarker
import com.bitacoraviaje.app.map.MapPoint
import com.bitacoraviaje.app.map.MapTap
import com.bitacoraviaje.core.geo.TrackLocator
import com.bitacoraviaje.core.geo.cumulativeDistanceM
import com.bitacoraviaje.core.geo.toIntervals
import com.bitacoraviaje.app.map.MarkerKind
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.export.ExportDestination
import com.bitacoraviaje.app.export.ExportOptions
import com.bitacoraviaje.app.export.ExportStatus
import com.bitacoraviaje.app.export.ExportWorker
import com.bitacoraviaje.app.media.LinkMediaWorker
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.timeline.TimelineEvent
import com.bitacoraviaje.core.timeline.TimelineInput
import com.bitacoraviaje.core.timeline.buildTimeline
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One segment's timeline plus what the map needs for it. */
data class SegmentTimeline(
    val segment: Segment,
    val events: List<TimelineEvent>,
    val track: List<MapPoint>,
    /** Accepted fixes in time order, for the interactive map (backlog D2). */
    val points: List<com.bitacoraviaje.core.model.TrackPoint> = emptyList(),
    val pauses: List<com.bitacoraviaje.core.geo.TimeInterval> = emptyList(),
    /** Distance covered up to each point, precomputed off the main thread (same rule as the stage stats). */
    val cumulativeM: DoubleArray = DoubleArray(0),
    /** Hourly weather samples (backlog D3). */
    val weather: List<com.bitacoraviaje.core.model.WeatherSample> = emptyList(),
)

/** What the user selected on the map, by stable keys: resolved against the current segments on every emission (backlog D2). */
data class MapSelectionRef(val segmentId: String, val eventKey: String?, val timeUtcMs: Long)

/** The card the map shows for a tapped marker or track point (backlog D2). [event] is null for a bare track point. */
data class MapSelection(
    val segment: Segment,
    val timeUtcMs: Long,
    val distanceM: Double?,
    val speedMps: Double?,
    val altM: Double?,
    val event: TimelineEvent?,
    /** "☀️ 23 °C" of the nearest sampled hour, when known (backlog D3). */
    val weather: String? = null,
)

/** A card of the Places tab (DISENO §14 "Lugares"): the place, how this segment saw it and its texts. */
data class PlaceCard(val segment: Segment, val place: com.bitacoraviaje.core.model.Place, val visit: com.bitacoraviaje.core.model.SegmentPlace, val texts: List<com.bitacoraviaje.core.model.Enrichment>)

/** What the user can do on a card (skill place-enrichment: use in the video, discard, edit the text). */
sealed interface PlaceAction {
    data class Approve(val enrichmentId: String, val approved: Boolean) : PlaceAction
    data class Discard(val segmentId: String, val placeId: String) : PlaceAction
    data class Edit(val enrichmentId: String, val text: String) : PlaceAction
    data class Rewrite(val enrichmentId: String) : PlaceAction
    data class Restore(val enrichmentId: String) : PlaceAction
    /** Look again: one segment (forced), or only the closed segments that have no places yet when null (review I4). */
    data class Refresh(val segmentId: String? = null) : PlaceAction
}

/** Enrichment work of this trip as the tab shows it. */
enum class PlacesWork { IDLE, WAITING_NETWORK, RUNNING }

data class TripDetailUiState(
    val trip: Trip? = null,
    val segments: List<SegmentTimeline> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val reviewCount: Int = 0,
    val map: TrackMapState = TrackMapState(),
    val selectedTab: Int = 0,
    val focusedEventKey: String? = null,
    val mapSelection: MapSelection? = null,
    val places: List<PlaceCard> = emptyList(),
    val placesWork: PlacesWork = PlacesWork.IDLE,
    val placesEnabled: Boolean = true,
    /** The user configured an LLM key: the rewrite action exists (PLAN 4.3). */
    val llmAvailable: Boolean = false,
    val rewriting: Set<String> = emptySet(),
    val placesMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext context: Context,
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
    private val places: com.bitacoraviaje.data.repository.PlacesRepository,
    private val weather: com.bitacoraviaje.data.repository.WeatherRepository,
    private val prefs: com.bitacoraviaje.app.settings.UserPreferences,
    private val secrets: com.bitacoraviaje.app.settings.Secrets,
    private val rewriter: com.bitacoraviaje.app.enrichment.LlmRewriter,
    private val offlineMaps: com.bitacoraviaje.app.map.OfflineMaps,
) : ViewModel() {
    // PLAN 5.4: offline map of this trip's area. The download itself runs in OfflineMaps' own scope.
    init {
        viewModelScope.launch { runCatching { offlineMaps.refresh() } }
    }

    /** One raw box per stage with points: the offline plan merges them into as few regions as fit. */
    private fun stageBoxes(map: TrackMapState): List<com.bitacoraviaje.core.model.BoundingBox> = stageBoxesOf(map.tracks)

    val offline: StateFlow<OfflineUiState> = combine(offlineMaps.active, offlineMaps.regions) { active, regions ->
        val mine = active?.takeIf { it.tripId == tripId }
        OfflineUiState(
            available = offlineMaps.isAvailable,
            hasRegion = regions?.any { it.tripId == tripId && it.complete } == true,
            running = mine != null && !mine.done,
            busyElsewhere = active != null && !active.done && active.tripId != tripId,
            progress = mine,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineUiState())

    fun startOfflineDownload(maxZoom: Int) {
        val plan = com.bitacoraviaje.app.map.OfflineMaps.plan(stageBoxes(state.value.map), maxZoom)?.takeIf { it.isNotEmpty() } ?: return
        offlineMaps.startPlan(tripId, state.value.trip?.title ?: appContext.getString(com.bitacoraviaje.app.R.string.offline_region_default_name), plan, com.bitacoraviaje.app.map.OfflineMaps.MIN_ZOOM, maxZoom)
    }

    /** Stops the download; the partial region stays listed in Settings, where it can be resumed. */
    fun cancelOfflineDownload() = offlineMaps.cancel()

    fun clearOfflineResult() = offlineMaps.clearResult()
    /** Number of privacy zones in Settings: the export dialog offers to apply them (PLAN 5.5). */
    val privacyZones: StateFlow<Int> = prefs.settings.map { it.privacyZones.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val rewriting = MutableStateFlow<Set<String>>(emptySet())
    private val placesMessage = MutableStateFlow<String?>(null)
    val tripId: String = savedState.toRoute<Route.TripDetail>().tripId
    /** Opened from the Home menu's "Exportar": the dialog shows at once (backlog D1). */
    val openExportOnStart: Boolean = savedState.toRoute<Route.TripDetail>().openExport
    private val appContext = context

    /** Finished export jobs the user dismissed with "Exportar de nuevo": hidden here instead of pruning WorkManager globally (review I8). */
    private val dismissedExports = MutableStateFlow<Set<java.util.UUID>>(emptySet())

    /** Export job of this trip as the UI sees it (PLAN 3.2). */
    val export: StateFlow<ExportStatus> = combine(
        androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ExportWorker.workName(tripId)),
        dismissedExports,
    ) { infos, dismissed ->
        val visible = infos.filter { it.id !in dismissed }
        ExportStatus.from(visible.firstOrNull { !it.state.isFinished } ?: visible.firstOrNull())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportStatus())

    fun startExport(options: ExportOptions, destination: ExportDestination, targetUri: android.net.Uri? = null) {
        ExportWorker.enqueue(appContext, tripId, options, destination, targetUri)
    }

    /**
     * Hides the finished job so the dialog offers a new export. `pruneWork()` would also drop the
     * import conflict and render results other screens still read (review I8).
     */
    fun dismissExport() {
        viewModelScope.launch {
            val finished = androidx.work.WorkManager.getInstance(appContext).getWorkInfosForUniqueWorkFlow(ExportWorker.workName(tripId)).first().filter { it.state.isFinished }.map { it.id }
            dismissedExports.value = dismissedExports.value + finished
        }
    }
    private val tab = MutableStateFlow(0)
    private val focus = MutableStateFlow<Pair<String, MapPoint>?>(null)
    private val selection = MutableStateFlow<MapSelectionRef?>(null)

    init {
        // Reconciliation sweep on every open of the trip detail (DISENO §7.3).
        LinkMediaWorker.enqueue(context, tripId)
        // Backlog D3: segments closed before the feature existed (or offline at the time) get their weather now.
        viewModelScope.launch {
            tracking.segmentsOf(tripId).filter { it.endedUtcMs != null && !weather.hasWeather(it.id) }
                .forEach { com.bitacoraviaje.app.weather.WeatherWorker.enqueue(context, it.id) }
        }
    }

    private val tripFlow = tracking.observeTrips().map { l -> l.firstOrNull { it.id == tripId } }

    private val segmentsFlow = tracking.observeSegments(tripId).flatMapLatest { segs ->
        if (segs.isEmpty()) {
            flow { emit(emptyList<SegmentTimeline>()) }
        } else {
            combine(segs.map { s -> perSegment(s) }) { it.toList() }
        }
    }

    /** Places of the whole trip joined with their visit rows and texts, best ranked first within each segment. */
    private val placesFlow = combine(places.observePlacesOfTrip(tripId), places.observeEnrichmentsOfTrip(tripId), places.observeSegmentPlacesOfTrip(tripId)) { pl, en, links ->
        links.mapNotNull { sp -> pl.firstOrNull { it.id == sp.placeId }?.let { p -> Triple(sp, p, en.filter { it.placeId == p.id }) } }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1) // one Room observer set for N segments (review M3)

    private class SegmentData(
        val pts: List<com.bitacoraviaje.core.model.TrackPoint>, val media: List<MediaItem>, val voice: List<com.bitacoraviaje.core.model.VoiceNote>,
        val text: List<com.bitacoraviaje.core.model.TextNote>, val stops: List<com.bitacoraviaje.core.model.Stop>, val pauses: List<com.bitacoraviaje.core.model.SegmentPause>,
    )

    private fun perSegment(s: Segment) = combine(
        combine(
            tracking.observeValidPoints(s.id),
            media.observeBySegment(s.id),
            notes.observeVoiceNotes(s.id),
            notes.observeTextNotes(s.id),
            flow { emit(tracking.stopsOf(s.id) to tracking.pausesOf(s.id)) },
        ) { pts, med, voice, text, (stops, pauses) -> SegmentData(pts, med, voice, text, stops, pauses) },
        placesFlow.map { l -> l.filter { it.first.segmentId == s.id }.map { it.second to it.first } },
        weather.observeSamples(s.id), // backlog D3
    ) { d, pl, wx ->
        val events = buildTimeline(TimelineInput(s, d.pts, d.pauses, d.stops, d.media, d.voice, d.text, places = pl, nowUtcMs = System.currentTimeMillis(), weather = wx))
        val intervals = d.pauses.toIntervals(System.currentTimeMillis())
        SegmentTimeline(s, events, d.pts.map { MapPoint(it.lat, it.lon) }, d.pts, intervals, cumulativeDistanceM(d.pts, intervals, s.transport), wx)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // timeline building off the main thread (review M5)

    /** Only this trip's enrichment jobs (tagged per segment), not every trip's (review M2). */
    private val placesWorkFlow = combine(androidx.work.WorkManager.getInstance(context).getWorkInfosByTagFlow(com.bitacoraviaje.app.enrichment.EnrichPlacesWorker.TAG_WORK), tracking.observeSegments(tripId)) { infos, segs ->
        val mine = segs.map { com.bitacoraviaje.app.enrichment.EnrichPlacesWorker.segmentTag(it.id) }.toSet()
        val own = infos.filter { info -> info.tags.any { it in mine } }
        when {
            own.any { it.state == androidx.work.WorkInfo.State.RUNNING } -> PlacesWork.RUNNING
            own.any { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED } -> PlacesWork.WAITING_NETWORK
            else -> PlacesWork.IDLE
        }
    }

    private val placeCards = combine(segmentsFlow, placesFlow) { segs, pl ->
        segs.flatMap { st -> pl.filter { it.first.segmentId == st.segment.id }.sortedByDescending { it.first.relevance ?: 0.0 }.map { (sp, p, texts) -> PlaceCard(st.segment, p, sp, texts) } }
    }

    private val llmFlow = combine(secrets.has(com.bitacoraviaje.app.settings.Secrets.LLM_API_KEY), rewriting, placesMessage) { k, r, m -> Triple(k, r, m) }
    private class FocusState(val focus: Pair<String, MapPoint>?, val selection: MapSelectionRef?)
    private val focusFlow = combine(focus, selection) { f, sel -> FocusState(f, sel) }

    val state: StateFlow<TripDetailUiState> = combine(tripFlow, segmentsFlow, media.observeByTrip(tripId), media.observeReviewQueue(tripId), tab, focusFlow, placeCards, placesWorkFlow, prefs.settings.map { it.enrichPlaces }, llmFlow) { arr ->
        @Suppress("UNCHECKED_CAST")
        val trip = arr[0] as Trip?
        @Suppress("UNCHECKED_CAST")
        val segs = arr[1] as List<SegmentTimeline>
        @Suppress("UNCHECKED_CAST")
        val med = arr[2] as List<MediaItem>
        @Suppress("UNCHECKED_CAST")
        val review = arr[3] as List<MediaItem>
        val selected = arr[4] as Int
        val fs = arr[5] as FocusState
        val f = fs.focus
        val sel = fs.selection?.let { resolveSelection(segs, it) }
        @Suppress("UNCHECKED_CAST")
        val cards = arr[6] as List<PlaceCard>
        val work = arr[7] as PlacesWork
        val enabled = arr[8] as Boolean
        @Suppress("UNCHECKED_CAST")
        val llm = arr[9] as Triple<Boolean, Set<String>, String?>
        val markers = segs.flatMap { st -> st.events.mapNotNull { e -> markerOf(e) } }
        val stops = segs.flatMap { st -> st.events.filterIsInstance<TimelineEvent.StopArrived>().map { MapPoint(it.stop.lat, it.stop.lon) } }
        TripDetailUiState(
            trip = trip, segments = segs, media = med, reviewCount = review.size,
            map = TrackMapState(tracks = segs.map { it.track }, stops = stops, markers = markers, follow = false, focus = f?.second),
            selectedTab = selected, focusedEventKey = f?.first, mapSelection = sel,
            places = cards, placesWork = work, placesEnabled = enabled, llmAvailable = llm.first, rewriting = llm.second, placesMessage = llm.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    fun selectTab(i: Int) {
        tab.value = i
    }

    fun onPlaceAction(a: PlaceAction) {
        viewModelScope.launch {
            when (a) {
                is PlaceAction.Approve -> places.setApproved(a.enrichmentId, a.approved)
                is PlaceAction.Discard -> places.discard(a.segmentId, a.placeId)
                is PlaceAction.Edit -> places.editSummary(a.enrichmentId, a.text.trim())
                is PlaceAction.Restore -> places.restoreSummary(a.enrichmentId)
                is PlaceAction.Rewrite -> rewrite(a.enrichmentId)
                is PlaceAction.Refresh -> {
                    val closed = tracking.segmentsOf(tripId).filter { it.endedUtcMs != null }
                    if (a.segmentId != null) {
                        com.bitacoraviaje.app.enrichment.EnrichPlacesWorker.enqueue(appContext, a.segmentId, force = true)
                    } else {
                        // Whole-trip button: only what is missing; a forced refresh is per segment (public instances, review I4).
                        closed.filter { !places.hasPlaces(it.id) }.forEach { com.bitacoraviaje.app.enrichment.EnrichPlacesWorker.enqueue(appContext, it.id, force = false) }
                    }
                }
            }
        }
    }

    /** Timeline → map: center on the event, show its card and switch to the map tab. */
    fun focusOn(event: TimelineEvent) {
        val la = event.lat ?: return
        val lo = event.lon ?: return
        val key = eventKey(event)
        focus.value = key to MapPoint(la, lo)
        // By key, not identity: the list may have re-emitted between the render and the tap (review I4).
        state.value.segments.firstOrNull { st -> st.events.any { eventKey(it) == key } }?.let { selection.value = MapSelectionRef(it.segment.id, key, event.timeUtcMs) }
        tab.value = 1
    }

    /**
     * Backlog D2: a marker opens its event, the track opens the nearest point of the line, anything else closes the card.
     * `state.value` is current because the screen collecting it is the one that delivers the tap. The nearest-fix
     * scan is O(n) per segment, so it runs off the main thread.
     */
    fun onMapTap(tap: MapTap) {
        val segs = state.value.segments
        when (tap) {
            is MapTap.Marker -> {
                val hit = segs.firstNotNullOfOrNull { st -> st.events.firstOrNull { eventKey(it) == tap.id }?.let { st to it } } ?: return
                focus.value = tap.id to MapPoint(hit.second.lat ?: return, hit.second.lon ?: return)
                selection.value = MapSelectionRef(hit.first.segment.id, tap.id, hit.second.timeUtcMs)
            }
            is MapTap.Spot -> viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val best = segs.mapNotNull { st -> TrackLocator.nearest(st.points, tap.point.lat, tap.point.lon, st.pauses, st.segment.transport, st.cumulativeM)?.let { st to it } }
                    .minByOrNull { it.second.offsetM }
                // On the track (within what a finger covers at this zoom) → its card; anywhere else closes the card.
                if (com.bitacoraviaje.app.BuildConfig.DEBUG) android.util.Log.d("Bitacora", "map spot best=${best?.first?.segment?.id?.take(8)} offsetM=${best?.second?.offsetM?.let { "%.1f".format(it) }} toleranceM=${"%.1f".format(tap.toleranceM)}")
                selection.value = if (best != null && best.second.offsetM <= tap.toleranceM) MapSelectionRef(best.first.segment.id, null, best.second.timeUtcMs) else null
            }
        }
    }

    fun clearSelection() {
        selection.value = null
    }

    /** A selection whose segment or event is gone resolves to nothing, so the card never shows stale rows (review M5). */
    private fun resolveSelection(segs: List<SegmentTimeline>, ref: MapSelectionRef): MapSelection? {
        val st = segs.firstOrNull { it.segment.id == ref.segmentId } ?: return null
        val event = ref.eventKey?.let { key -> st.events.firstOrNull { eventKey(it) == key } ?: return null }
        val pos = TrackLocator.atTime(st.points, ref.timeUtcMs, st.pauses, st.segment.transport, st.cumulativeM.takeIf { it.size == st.points.size })
        return MapSelection(st.segment, ref.timeUtcMs, pos?.distanceM, pos?.speedMps, pos?.altM, event, com.bitacoraviaje.core.timeline.WeatherDigest.brief(com.bitacoraviaje.core.timeline.WeatherDigest.at(st.weather, ref.timeUtcMs)))
    }

    /** DISENO §11.4: only the fetched text and the Wikidata facts go to the model; the result replaces the summary, the original stays. */
    private suspend fun rewrite(enrichmentId: String) {
        val key = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { secrets.get(com.bitacoraviaje.app.settings.Secrets.LLM_API_KEY) } ?: return
        val all = places.enrichmentsOfTrip(tripId)
        val e = all.firstOrNull { it.id == enrichmentId } ?: return
        val facts = all.filter { it.placeId == e.placeId && it.sourceName == com.bitacoraviaje.core.model.Enrichment.SOURCE_WIKIDATA }.flatMap { it.summary.split(" · ") }
        val name = places.getPlace(e.placeId)?.name ?: e.title ?: ""
        rewriting.value += enrichmentId
        placesMessage.value = null
        try {
            val model = prefs.settings.first().llmModel
            val out = rewriter.rewrite(com.bitacoraviaje.app.enrichment.RewriteRequest(name, e.originalSummary ?: e.summary, facts), key, model)
            if (out == null) placesMessage.value = appContext.getString(com.bitacoraviaje.app.R.string.places_rewrite_none) else places.setRewritten(enrichmentId, out)
        } catch (ex: Exception) {
            if (ex is kotlinx.coroutines.CancellationException) throw ex
            android.util.Log.w("Bitacora", "rewrite failed: ${ex.javaClass.simpleName} ${ex.message}")
            placesMessage.value = appContext.getString(com.bitacoraviaje.app.R.string.places_rewrite_failed, ex.message ?: ex.javaClass.simpleName)
        } finally {
            rewriting.value -= enrichmentId
        }
    }

    companion object {
        /**
         * Stable identity of an event for LazyColumn keys, map marker ids and focus. Class + time alone collided
         * (two photos taken the same millisecond far apart are two moments) and crashed the list; events that
         * carry a row use its id.
         */
        fun eventKey(e: TimelineEvent): String = when (e) {
            is TimelineEvent.Moment -> "Moment-${e.timeUtcMs}-${e.media.firstOrNull()?.id}-${e.media.size}"
            is TimelineEvent.Voice -> "Voice-${e.note.id}"
            is TimelineEvent.Text -> "Text-${e.note.id}"
            is TimelineEvent.Highlight -> "Highlight-${e.note.id}"
            is TimelineEvent.TransportChange -> "TransportChange-${e.note.id}"
            is TimelineEvent.StopArrived -> "StopArrived-${e.stop.id}"
            is TimelineEvent.StopLeft -> "StopLeft-${e.stop.id}"
            is TimelineEvent.PlaceVisited -> "PlaceVisited-${e.place.id}-${e.timeUtcMs}"
            else -> "${e::class.simpleName}-${e.timeUtcMs}"
        }

        fun markerOf(e: TimelineEvent): MapMarker? {
            val la = e.lat ?: return null
            val lo = e.lon ?: return null
            val kind = when (e) {
                is TimelineEvent.Moment -> if (e.hasVideo) MarkerKind.VIDEO else MarkerKind.PHOTO
                is TimelineEvent.PlaceVisited -> MarkerKind.PLACE
                is TimelineEvent.Voice -> MarkerKind.VOICE
                is TimelineEvent.Text -> MarkerKind.TEXT
                is TimelineEvent.Highlight -> MarkerKind.HIGHLIGHT
                else -> return null
            }
            return MapMarker(eventKey(e), MapPoint(la, lo), kind)
        }

        fun MediaItem.isPhoto() = kind == MediaKind.PHOTO
    }
}
