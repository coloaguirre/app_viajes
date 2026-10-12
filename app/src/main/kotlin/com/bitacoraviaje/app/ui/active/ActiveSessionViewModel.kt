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
import com.bitacoraviaje.app.voice.NotesController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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
    data object VoiceNote : ActiveSessionAction
    data class Marker(val text: String?) : ActiveSessionAction
    data object Highlight : ActiveSessionAction
    data class ChangeTransport(val transport: com.bitacoraviaje.core.model.Transport) : ActiveSessionAction
}

sealed interface ActiveSessionEvent {
    data object OpenVoiceNote : ActiveSessionEvent
    data class Saved(val kind: com.bitacoraviaje.core.model.TextNoteKind) : ActiveSessionEvent
    data class TransportChanged(val transport: com.bitacoraviaje.core.model.Transport) : ActiveSessionEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActiveSessionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: TrackingRepository,
    private val controller: TrackingController,
    stateHolder: TrackingStateHolder,
    private val notes: NotesController,
) : ViewModel() {
    private val _events = Channel<ActiveSessionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val segmentId: String = savedState.toRoute<Route.ActiveSession>().segmentId

    // The service may hand recording to another segment (day cut, nested hand-back): follow the live one.
    private val liveSegmentId = stateHolder.state.map { it.segmentId ?: segmentId }.distinctUntilChanged()
    private val segmentRefresh = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val segmentFlow = combine(liveSegmentId, segmentRefresh) { id, _ -> id }.flatMapLatest { id -> flow { emit(repository.getSegment(id)) } }
    private val trackFlow = liveSegmentId.flatMapLatest { id -> repository.observeValidPoints(id) }
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    private val mapFlow = combine(trackFlow, stateHolder.state.map { it.lastPoint }.distinctUntilChanged()) { track, last ->
        TrackMapState(track = track.map { MapPoint(it.lat, it.lon) }, current = last?.let { MapPoint(it.lat, it.lon) })
    }

    val state: StateFlow<ActiveSessionUiState> = combine(segmentFlow, stateHolder.state, ticker, mapFlow) { seg, live, now, map ->
        ActiveSessionUiState(segment = seg, live = live, elapsedMs = live.elapsedMs(now), map = map)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveSessionUiState())

    fun onAction(a: ActiveSessionAction) {
        when (a) {
            ActiveSessionAction.TogglePause -> if (state.value.live.isPaused) controller.resume() else controller.pause()
            ActiveSessionAction.Finish -> Unit // navigation handled by the route
            ActiveSessionAction.VoiceNote -> viewModelScope.launch { _events.send(ActiveSessionEvent.OpenVoiceNote) }
            is ActiveSessionAction.Marker -> viewModelScope.launch { notes.addMarker(a.text)?.let { _events.send(ActiveSessionEvent.Saved(it.kind)) } }
            ActiveSessionAction.Highlight -> viewModelScope.launch { notes.addHighlight()?.let { _events.send(ActiveSessionEvent.Saved(it.kind)) } }
            is ActiveSessionAction.ChangeTransport -> viewModelScope.launch {
                if (controller.changeTransport(a.transport)) {
                    segmentRefresh.value++
                    _events.send(ActiveSessionEvent.TransportChanged(a.transport))
                }
            }
        }
    }
}
