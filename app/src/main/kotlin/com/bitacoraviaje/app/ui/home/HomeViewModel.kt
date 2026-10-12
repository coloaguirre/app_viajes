package com.bitacoraviaje.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(val trips: List<Trip> = emptyList(), val openSegment: Segment? = null)

sealed interface HomeEvent {
    data class OpenActiveSession(val segmentId: String) : HomeEvent
    data object PermissionDenied : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TrackingRepository,
    private val controller: TrackingController,
    private val prefs: UserPreferences,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(repository.observeTrips(), repository.observeOpenSegment()) { trips, open ->
        HomeUiState(trips, open)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun requiredPermissions() = controller.requiredPermissions()

    /**
     * Quick start used until the new-session wizard lands (item 1.6): a walking
     * stage with the recording mode chosen in Settings. Call only after permissions
     * were requested, with the app visible.
     */
    fun startQuickSession() {
        viewModelScope.launch {
            if (!controller.hasLocationPermission()) {
                _events.send(HomeEvent.PermissionDenied)
                return@launch
            }
            val mode = prefs.settings.first().recordingMode
            val open = repository.getOpenSegment()
            val id = open?.id ?: controller.startSession(SegmentType.STAGE, Transport.WALK, mode)
            _events.send(HomeEvent.OpenActiveSession(id))
        }
    }
}
