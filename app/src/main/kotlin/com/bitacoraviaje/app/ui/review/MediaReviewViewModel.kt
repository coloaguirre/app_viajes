package com.bitacoraviaje.app.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.map.MapMarker
import com.bitacoraviaje.app.map.MapPoint
import com.bitacoraviaje.app.map.MarkerKind
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.media.MediaLinker
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.core.geo.cameraClockOffsetMs
import com.bitacoraviaje.core.geo.positionAt
import com.bitacoraviaje.core.geo.PositionAt
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MediaReviewUiState(
    val queue: List<MediaItem> = emptyList(),
    val selected: MediaItem? = null,
    val map: TrackMapState = TrackMapState(),
    /** Offset the camera of the last manually placed photo seems to have, to offer "fix this camera". */
    val suggestedOffsetMs: Long? = null,
    val suggestedCamera: String? = null,
)

/** DISENO §14.1 item 7: place unplaced or doubtful media by hand, exclude, or fix a camera's clock. */
@HiltViewModel
class MediaReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val media: MediaRepository,
    private val tracking: TrackingRepository,
    private val linker: MediaLinker,
) : ViewModel() {
    val tripId: String = savedState.toRoute<Route.MediaReview>().tripId
    private val selectedId = MutableStateFlow<String?>(null)
    private val suggestion = MutableStateFlow<Pair<Long, String>?>(null)
    private var tracks: Map<String, List<TrackPoint>> = emptyMap()
    private val tracksLoaded = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            tracks = tracking.segmentsOf(tripId).associate { it.id to tracking.validPoints(it.id) }
            tracksLoaded.value = true
        }
    }

    val state: StateFlow<MediaReviewUiState> = combine(media.observeReviewQueue(tripId), selectedId, suggestion, tracksLoaded) { queue, sel, sug, _ ->
        val selected = queue.firstOrNull { it.id == sel } ?: queue.firstOrNull()
        val focus = selected?.let { m -> m.linkedLat?.let { la -> m.linkedLon?.let { lo -> MapPoint(la, lo) } } ?: m.exifLat?.let { la -> m.exifLon?.let { lo -> MapPoint(la, lo) } } }
        MediaReviewUiState(
            queue = queue,
            selected = selected,
            map = TrackMapState(
                tracks = tracks.values.map { pts -> pts.map { MapPoint(it.lat, it.lon) } },
                markers = listOfNotNull(focus?.let { MapMarker(selected!!.id, it, MarkerKind.PLACED) }),
                follow = false,
                focus = focus,
            ),
            suggestedOffsetMs = sug?.first,
            suggestedCamera = sug?.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaReviewUiState())

    fun select(id: String) {
        selectedId.value = id
    }

    /** Map tap: place the selected item there; remember the implied camera offset for "fix this camera". */
    fun placeAt(p: MapPoint) {
        val m = state.value.selected ?: return
        viewModelScope.launch {
            media.placeManually(m.id, p.lat, p.lon)
            val pts = m.segmentId?.let { tracks[it] } ?: tracks.values.flatten()
            val off = cameraClockOffsetMs(pts, p.lat, p.lon, m.takenUtcMs)
            val camera = listOfNotNull(m.cameraMake, m.cameraModel).joinToString(" ").ifBlank { null }
            if (off != null && camera != null && kotlin.math.abs(off) >= 60_000) suggestion.value = off to camera else suggestion.value = null
        }
    }

    /** "Use the track time": ignore EXIF GPS and place from the track at the capture time. */
    fun useTrackTime() {
        val m = state.value.selected ?: return
        val pts = m.segmentId?.let { tracks[it] } ?: return
        when (val pos = positionAt(pts, m.takenUtcMs)) {
            is PositionAt.Interpolated -> viewModelScope.launch { media.placeManually(m.id, pos.lat, pos.lon) }
            is PositionAt.Nearest -> viewModelScope.launch { media.placeManually(m.id, pos.lat, pos.lon) }
            PositionAt.None -> Unit
        }
    }

    fun exclude() {
        val m = state.value.selected ?: return
        viewModelScope.launch { media.setExcluded(m.id, true) }
    }

    /** Shift every photo of the suggested camera by the offset and re-link them (DISENO §7.6 step 4). */
    fun applyCameraOffset() {
        val (off, camera) = suggestion.value ?: return
        viewModelScope.launch {
            linker.applyCameraOffset(tripId, camera, off)
            suggestion.value = null
        }
    }
}
