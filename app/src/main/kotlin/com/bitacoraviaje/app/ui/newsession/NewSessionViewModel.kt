package com.bitacoraviaje.app.ui.newsession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NewSessionForm(
    val type: SegmentType? = null,
    val tripTitle: String = "",
    val title: String = "",
    val intentText: String = "",
    val transport: Transport = Transport.WALK,
    val mode: RecordingMode = RecordingMode.FULL,
)

data class NewSessionUiState(
    val form: NewSessionForm = NewSessionForm(),
    /** An open segment means "Excursión/Trekking" nest under it and "Etapa" closes it first. */
    val openSegment: Segment? = null,
    val hasActiveTrip: Boolean = false,
    val starting: Boolean = false,
) {
    val canStart: Boolean get() = form.type != null && !starting
}

sealed interface NewSessionEvent {
    data class Started(val segmentId: String) : NewSessionEvent
    data object PermissionDenied : NewSessionEvent
}

@HiltViewModel
class NewSessionViewModel @Inject constructor(
    private val repository: TrackingRepository,
    private val controller: TrackingController,
    prefs: UserPreferences,
) : ViewModel() {
    private val form = MutableStateFlow(NewSessionForm())
    private val starting = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val settings = prefs.settings.first()
            form.value = form.value.copy(mode = settings.recordingMode)
        }
    }

    val state: StateFlow<NewSessionUiState> = combine(form, repository.observeOpenSegment(), repository.observeActiveTrip(), starting) { f, open, trip, st ->
        NewSessionUiState(f, open, trip != null, st)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewSessionUiState())

    private val _events = Channel<NewSessionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun requiredPermissions() = controller.requiredPermissions()

    fun update(transform: (NewSessionForm) -> NewSessionForm) {
        form.value = transform(form.value)
    }

    /** Call after the permission request returned, with the app visible. */
    fun start() {
        val f = form.value
        val type = f.type ?: return
        if (starting.value) return
        starting.value = true
        viewModelScope.launch {
            try {
                if (!controller.hasLocationPermission()) {
                    _events.send(NewSessionEvent.PermissionDenied)
                    return@launch
                }
                val id = controller.startSession(
                    type = type,
                    transport = f.transport,
                    mode = f.mode,
                    title = f.title.ifBlank { null },
                    intentText = f.intentText.ifBlank { null },
                    tripTitle = f.tripTitle,
                )
                _events.send(NewSessionEvent.Started(id))
            } finally {
                starting.value = false
            }
        }
    }
}
