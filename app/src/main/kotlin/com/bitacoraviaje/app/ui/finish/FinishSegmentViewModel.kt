package com.bitacoraviaje.app.ui.finish

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FinishSegmentUiState(
    val segment: Segment? = null,
    val liveDistanceM: Double = 0.0,
    val liveElapsedMs: Long = 0,
    val pointCount: Int = 0,
    val stopCount: Int = 0,
    val closing: Boolean = false,
    /** Set once the segment (or trip) was closed; shows the final stats. */
    val closed: Segment? = null,
    val tripClosed: Boolean = false,
    /** Parent that resumed recording after a nested excursion ended (adenda 1). */
    val resumedParentId: String? = null,
)

@HiltViewModel
class FinishSegmentViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: TrackingRepository,
    private val controller: TrackingController,
    stateHolder: TrackingStateHolder,
) : ViewModel() {
    private val segmentId: String = savedState.toRoute<Route.FinishSegment>().segmentId
    private val _state = MutableStateFlow(FinishSegmentUiState())
    val state: StateFlow<FinishSegmentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val seg = repository.getSegment(segmentId)
            val live = stateHolder.state.value
            _state.value = FinishSegmentUiState(
                segment = seg,
                liveDistanceM = live.distanceM,
                liveElapsedMs = live.elapsedMs(System.currentTimeMillis()),
                pointCount = repository.validPoints(segmentId).size,
            )
        }
    }

    fun closeSegment() = close(trip = false)

    fun closeTrip() = close(trip = true)

    private fun close(trip: Boolean) {
        if (_state.value.closing || _state.value.closed != null) return
        _state.value = _state.value.copy(closing = true)
        viewModelScope.launch {
            val seg = _state.value.segment
            val resumedParent = if (trip && seg != null) {
                controller.finishTrip(seg.tripId)
                null
            } else {
                controller.finishSegment(segmentId)
            }
            val done = repository.getSegment(segmentId)
            _state.value = _state.value.copy(
                closing = false,
                closed = done?.takeIf { it.status == SegmentStatus.FINISHED || it.status == SegmentStatus.RECOVERED },
                tripClosed = trip,
                stopCount = repository.stopsOf(segmentId).size,
                resumedParentId = resumedParent,
            )
        }
    }
}
