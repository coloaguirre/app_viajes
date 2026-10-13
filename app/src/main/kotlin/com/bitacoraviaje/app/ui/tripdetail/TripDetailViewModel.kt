package com.bitacoraviaje.app.ui.tripdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.map.MapMarker
import com.bitacoraviaje.app.map.MapPoint
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One segment's timeline plus what the map needs for it. */
data class SegmentTimeline(val segment: Segment, val events: List<TimelineEvent>, val track: List<MapPoint>)

/** A card of the Places tab (DISENO §14 "Lugares"): the place, how this segment saw it and its texts. */
data class PlaceCard(val segment: Segment, val place: com.bitacoraviaje.core.model.Place, val visit: com.bitacoraviaje.core.model.SegmentPlace, val texts: List<com.bitacoraviaje.core.model.Enrichment>)

/** What the user can do on a card (skill place-enrichment: use in the video, discard, edit the text). */
sealed interface PlaceAction {
    data class Approve(val enrichmentId: String, val approved: Boolean) : PlaceAction
    data class Discard(val segmentId: String, val placeId: String) : PlaceAction
    data class Edit(val enrichmentId: String, val text: String) : PlaceAction
    data class Rewrite(val enrichmentId: String) : PlaceAction
    data class Restore(val enrichmentId: String) : PlaceAction
    data object Refresh : PlaceAction
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
    private val prefs: com.bitacoraviaje.app.settings.UserPreferences,
    private val secrets: com.bitacoraviaje.app.settings.Secrets,
    private val rewriter: com.bitacoraviaje.app.enrichment.LlmRewriter,
) : ViewModel() {
    private val rewriting = MutableStateFlow<Set<String>>(emptySet())
    private val placesMessage = MutableStateFlow<String?>(null)
    val tripId: String = savedState.toRoute<Route.TripDetail>().tripId
    private val appContext = context

    /** Export job of this trip as the UI sees it (PLAN 3.2). */
    val export: StateFlow<ExportStatus> = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ExportWorker.workName(tripId))
        .map { infos -> ExportStatus.from(infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportStatus())

    fun startExport(options: ExportOptions, destination: ExportDestination, targetUri: android.net.Uri? = null) {
        ExportWorker.enqueue(appContext, tripId, options, destination, targetUri)
    }

    /** Clears a finished job so the dialog can offer a new export. */
    fun dismissExport() {
        androidx.work.WorkManager.getInstance(appContext).pruneWork()
    }
    private val tab = MutableStateFlow(0)
    private val focus = MutableStateFlow<Pair<String, MapPoint>?>(null)

    init {
        // Reconciliation sweep on every open of the trip detail (DISENO §7.3).
        LinkMediaWorker.enqueue(context, tripId)
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
    }

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
    ) { d, pl ->
        val events = buildTimeline(TimelineInput(s, d.pts, d.pauses, d.stops, d.media, d.voice, d.text, places = pl, nowUtcMs = System.currentTimeMillis()))
        SegmentTimeline(s, events, d.pts.map { MapPoint(it.lat, it.lon) })
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // timeline building off the main thread (review M5)

    private val placesWorkFlow = androidx.work.WorkManager.getInstance(context).getWorkInfosByTagFlow(com.bitacoraviaje.app.enrichment.EnrichPlacesWorker.TAG_WORK).map { infos ->
        when {
            infos.any { it.state == androidx.work.WorkInfo.State.RUNNING } -> PlacesWork.RUNNING
            infos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED } -> PlacesWork.WAITING_NETWORK
            else -> PlacesWork.IDLE
        }
    }

    private val placeCards = combine(segmentsFlow, placesFlow) { segs, pl ->
        segs.flatMap { st -> pl.filter { it.first.segmentId == st.segment.id }.sortedByDescending { it.first.relevance ?: 0.0 }.map { (sp, p, texts) -> PlaceCard(st.segment, p, sp, texts) } }
    }

    private val llmFlow = combine(secrets.has(com.bitacoraviaje.app.settings.Secrets.LLM_API_KEY), rewriting, placesMessage) { k, r, m -> Triple(k, r, m) }

    val state: StateFlow<TripDetailUiState> = combine(tripFlow, segmentsFlow, media.observeByTrip(tripId), media.observeReviewQueue(tripId), tab, focus, placeCards, placesWorkFlow, prefs.settings.map { it.enrichPlaces }, llmFlow) { arr ->
        @Suppress("UNCHECKED_CAST")
        val trip = arr[0] as Trip?
        @Suppress("UNCHECKED_CAST")
        val segs = arr[1] as List<SegmentTimeline>
        @Suppress("UNCHECKED_CAST")
        val med = arr[2] as List<MediaItem>
        @Suppress("UNCHECKED_CAST")
        val review = arr[3] as List<MediaItem>
        val selected = arr[4] as Int
        @Suppress("UNCHECKED_CAST")
        val f = arr[5] as Pair<String, MapPoint>?
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
            selectedTab = selected, focusedEventKey = f?.first,
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
                PlaceAction.Refresh -> tracking.segmentsOf(tripId).filter { it.endedUtcMs != null }.forEach { com.bitacoraviaje.app.enrichment.EnrichPlacesWorker.enqueue(appContext, it.id, force = true) }
            }
        }
    }

    /** Timeline → map: center on the event and switch to the map tab. */
    fun focusOn(event: TimelineEvent) {
        val la = event.lat ?: return
        val lo = event.lon ?: return
        focus.value = eventKey(event) to MapPoint(la, lo)
        tab.value = 1
    }

    /** DISENO §11.4: only the fetched text and the Wikidata facts go to the model; the result replaces the summary, the original stays. */
    private suspend fun rewrite(enrichmentId: String) {
        val key = secrets.get(com.bitacoraviaje.app.settings.Secrets.LLM_API_KEY) ?: return
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
        fun eventKey(e: TimelineEvent): String = "${e::class.simpleName}-${e.timeUtcMs}"

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
