@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // Media3 Transformer API (PLAN 4.6)

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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
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

    /** PLAN 4.6: the 1-minute video job of this stage as the screen sees it. */
    val render: StateFlow<com.bitacoraviaje.app.video.RenderStatus> = androidx.work.WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(com.bitacoraviaje.app.video.RenderWorker.workName(segmentId))
        .map { infos -> com.bitacoraviaje.app.video.RenderStatus.from(infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), com.bitacoraviaje.app.video.RenderStatus())

    fun createVideo() = com.bitacoraviaje.app.video.RenderWorker.enqueue(appContext, segmentId)

    /** KEEP only keeps pending or running work: a finished job is simply replaced by a new request (review N9). */
    fun retryVideo() = createVideo()

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
