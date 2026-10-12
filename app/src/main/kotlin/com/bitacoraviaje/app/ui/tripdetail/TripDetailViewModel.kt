package com.bitacoraviaje.app.ui.tripdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.map.MapMarker
import com.bitacoraviaje.app.map.MapPoint
import com.bitacoraviaje.app.map.MarkerKind
import com.bitacoraviaje.app.map.TrackMapState
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One segment's timeline plus what the map needs for it. */
data class SegmentTimeline(val segment: Segment, val events: List<TimelineEvent>, val track: List<MapPoint>)

data class TripDetailUiState(
    val trip: Trip? = null,
    val segments: List<SegmentTimeline> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val reviewCount: Int = 0,
    val map: TrackMapState = TrackMapState(),
    val selectedTab: Int = 0,
    val focusedEventKey: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext context: Context,
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
) : ViewModel() {
    val tripId: String = savedState.toRoute<Route.TripDetail>().tripId
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

    private fun perSegment(s: Segment) = combine(
        tracking.observeValidPoints(s.id),
        media.observeBySegment(s.id),
        notes.observeVoiceNotes(s.id),
        notes.observeTextNotes(s.id),
        flow { emit(tracking.stopsOf(s.id) to tracking.pausesOf(s.id)) },
    ) { pts, med, voice, text, (stops, pauses) ->
        val events = buildTimeline(TimelineInput(s, pts, pauses, stops, med, voice, text, nowUtcMs = System.currentTimeMillis()))
        SegmentTimeline(s, events, pts.map { MapPoint(it.lat, it.lon) })
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // timeline building off the main thread (review M5)

    val state: StateFlow<TripDetailUiState> = combine(tripFlow, segmentsFlow, media.observeByTrip(tripId), media.observeReviewQueue(tripId), tab, focus) { arr ->
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
        val markers = segs.flatMap { st -> st.events.mapNotNull { e -> markerOf(e) } }
        val stops = segs.flatMap { st -> st.events.filterIsInstance<TimelineEvent.StopArrived>().map { MapPoint(it.stop.lat, it.stop.lon) } }
        TripDetailUiState(
            trip = trip, segments = segs, media = med, reviewCount = review.size,
            map = TrackMapState(tracks = segs.map { it.track }, stops = stops, markers = markers, follow = false, focus = f?.second),
            selectedTab = selected, focusedEventKey = f?.first,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    fun selectTab(i: Int) {
        tab.value = i
    }

    /** Timeline → map: center on the event and switch to the map tab. */
    fun focusOn(event: TimelineEvent) {
        val la = event.lat ?: return
        val lo = event.lon ?: return
        focus.value = eventKey(event) to MapPoint(la, lo)
        tab.value = 1
    }

    companion object {
        fun eventKey(e: TimelineEvent): String = "${e::class.simpleName}-${e.timeUtcMs}"

        fun markerOf(e: TimelineEvent): MapMarker? {
            val la = e.lat ?: return null
            val lo = e.lon ?: return null
            val kind = when (e) {
                is TimelineEvent.Moment -> if (e.hasVideo) MarkerKind.VIDEO else MarkerKind.PHOTO
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
