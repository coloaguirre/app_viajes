package com.bitacoraviaje.app.ui.active

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.tracking.LiveTrackingState
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActiveSessionUiState(
    val segment: Segment? = null,
    val live: LiveTrackingState = LiveTrackingState(),
    val elapsedMs: Long = 0,
    val showFinishDialog: Boolean = false,
)

sealed interface ActiveSessionAction {
    data object TogglePause : ActiveSessionAction
    data object AskFinish : ActiveSessionAction
    data object DismissFinish : ActiveSessionAction
    data object FinishSegment : ActiveSessionAction
    data object FinishTrip : ActiveSessionAction
}

sealed interface ActiveSessionEvent {
    data object Closed : ActiveSessionEvent
}

@HiltViewModel
class ActiveSessionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: TrackingRepository,
    private val controller: TrackingController,
    stateHolder: TrackingStateHolder,
) : ViewModel() {
    private val segmentId: String = savedState.toRoute<Route.ActiveSession>().segmentId
    private val segmentFlow = flow { emit(repository.getSegment(segmentId)) }
    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
    private val dialog = kotlinx.coroutines.flow.MutableStateFlow(false)

    val state: StateFlow<ActiveSessionUiState> = combine(segmentFlow, stateHolder.state, ticker, dialog) { seg, live, now, dlg ->
        ActiveSessionUiState(seg, live, live.elapsedMs(now), dlg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveSessionUiState())

    private val _events = Channel<ActiveSessionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onAction(a: ActiveSessionAction) {
        when (a) {
            ActiveSessionAction.TogglePause -> if (state.value.live.isPaused) controller.resume() else controller.pause()
            ActiveSessionAction.AskFinish -> dialog.value = true
            ActiveSessionAction.DismissFinish -> dialog.value = false
            ActiveSessionAction.FinishSegment -> viewModelScope.launch {
                controller.finishSegment(segmentId)
                dialog.value = false
                _events.send(ActiveSessionEvent.Closed)
            }
            ActiveSessionAction.FinishTrip -> viewModelScope.launch {
                state.value.segment?.let { controller.finishTrip(it.tripId) } ?: controller.finishSegment(segmentId)
                dialog.value = false
                _events.send(ActiveSessionEvent.Closed)
            }
        }
    }
}
