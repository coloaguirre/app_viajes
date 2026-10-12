package com.bitacoraviaje.app.ui.active

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.map.MapPoint
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.tracking.LiveTrackingState
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ActiveSessionUiState(
    val segment: Segment? = null,
    val live: LiveTrackingState = LiveTrackingState(),
    val elapsedMs: Long = 0,
    val map: TrackMapState = TrackMapState(),
)

sealed interface ActiveSessionAction {
    data object TogglePause : ActiveSessionAction
    data object Finish : ActiveSessionAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActiveSessionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: TrackingRepository,
    private val controller: TrackingController,
    stateHolder: TrackingStateHolder,
) : ViewModel() {
    val segmentId: String = savedState.toRoute<Route.ActiveSession>().segmentId

    // The service may hand recording to another segment (day cut, nested hand-back): follow the live one.
    private val liveSegmentId = stateHolder.state.map { it.segmentId ?: segmentId }.distinctUntilChanged()
    private val segmentFlow = liveSegmentId.flatMapLatest { id -> flow { emit(repository.getSegment(id)) } }
    private val trackFlow = liveSegmentId.flatMapLatest { id -> repository.observeValidPoints(id) }
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val state: StateFlow<ActiveSessionUiState> = combine(segmentFlow, stateHolder.state, ticker, trackFlow) { seg, live, now, track ->
        ActiveSessionUiState(
            segment = seg,
            live = live,
            elapsedMs = live.elapsedMs(now),
            map = TrackMapState(
                track = track.map { MapPoint(it.lat, it.lon) },
                current = live.lastPoint?.let { MapPoint(it.lat, it.lon) },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveSessionUiState())

    fun onAction(a: ActiveSessionAction) {
        when (a) {
            ActiveSessionAction.TogglePause -> if (state.value.live.isPaused) controller.resume() else controller.pause()
            ActiveSessionAction.Finish -> Unit // navigation handled by the route
        }
    }
}
