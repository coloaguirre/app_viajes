package com.bitacoraviaje.app.ui.newsession

import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import androidx.lifecycle.viewModelScope
import com.bitacoraviaje.app.media.MediaAccess
import com.bitacoraviaje.app.media.MediaPermissions
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.Trip
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

/** Backlog D4: what the form is for. Null while the one-tap entry decides where to go. */
enum class NewSessionMode { NEW_TRIP, ADD_TO_TRIP }

data class NewSessionUiState(
    val form: NewSessionForm = NewSessionForm(),
    /** An open segment means "Excursión/Trekking" nest under it and "Etapa" closes it first. */
    val openSegment: Segment? = null,
    val starting: Boolean = false,
    val mediaAccess: MediaAccess = MediaAccess.NONE,
    val mode: NewSessionMode? = null,
    /** The trip the component is added to (ADD_TO_TRIP). */
    val targetTrip: Trip? = null,
    /** The trip recording right now, when it is not [targetTrip]: starting closes it (and its open segment). */
    val activeTrip: Trip? = null,
) {
    val canStart: Boolean get() = form.type != null && !starting && mode != null
    val closesOtherTrip: Boolean get() = activeTrip != null && activeTrip.id != targetTrip?.id
}

sealed interface NewSessionEvent {
    data class Started(val segmentId: String) : NewSessionEvent
    data object PermissionDenied : NewSessionEvent
    /** Leave this screen: the trip to add to is gone, or an interrupted segment needs Home's recovery dialog (review I7/M2 of D4). */
    data object GoHome : NewSessionEvent
    data class Failed(val detail: String) : NewSessionEvent
}

@HiltViewModel
class NewSessionViewModel @Inject constructor(
    savedState: androidx.lifecycle.SavedStateHandle,
    private val repository: TrackingRepository,
    private val controller: TrackingController,
    prefs: UserPreferences,
    private val mediaPermissions: MediaPermissions,
) : ViewModel() {
    private val route = savedState.toRoute<com.bitacoraviaje.app.ui.navigation.Route.NewSession>()
    private val form = MutableStateFlow(NewSessionForm())
    private val starting = MutableStateFlow(false)
    private val mediaAccess = MutableStateFlow(mediaPermissions.access())
    private val mode = MutableStateFlow<NewSessionMode?>(null)
    private val targetTrip = MutableStateFlow<Trip?>(null)
    private val _events = Channel<NewSessionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val settings = prefs.settings.first()
            form.value = form.value.copy(mode = settings.recordingMode)
            resolveMode()
        }
    }

    /**
     * Backlog D4. Explicit routes set the mode. The bare route is the one-tap entry (shortcut, tile, widget):
     * continue the open segment, else start a stage of the active trip right away, else offer a new trip.
     */
    private suspend fun resolveMode() {
        when {
            route.newTrip -> {
                mode.value = NewSessionMode.NEW_TRIP
                form.value = form.value.copy(type = SegmentType.TRIP_START)
            }
            route.tripId != null -> {
                val trip = repository.getTrip(route.tripId)
                if (trip == null) { _events.send(NewSessionEvent.GoHome); return } // deleted meanwhile: never add to "whatever is active"
                targetTrip.value = trip
                mode.value = NewSessionMode.ADD_TO_TRIP
                form.value = form.value.copy(type = SegmentType.STAGE)
            }
            else -> {
                if (controller.needsRecovery()) { _events.send(NewSessionEvent.GoHome); return } // Home's dialog decides: resume or close (DISENO §6.9)
                val active = repository.getActiveTrip()
                if (active != null && controller.hasLocationPermission()) {
                    starting.value = true
                    try {
                        controller.continueRecording()?.let { _events.send(NewSessionEvent.Started(it)); return }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // Never crash the one-tap entry (review I2): fall through to the form, which shows what failed.
                        android.util.Log.w("Bitacora", "one-tap continue failed: ${e.javaClass.simpleName} ${e.message}")
                        _events.send(NewSessionEvent.Failed(e.message ?: e.javaClass.simpleName))
                    } finally {
                        starting.value = false
                    }
                }
                if (active != null) {
                    targetTrip.value = active
                    mode.value = NewSessionMode.ADD_TO_TRIP
                    form.value = form.value.copy(type = SegmentType.STAGE)
                } else {
                    mode.value = NewSessionMode.NEW_TRIP
                    form.value = form.value.copy(type = SegmentType.TRIP_START)
                }
            }
        }
    }

    private val flags = combine(starting, mediaAccess, mode, targetTrip) { st, ma, m, t -> Flags(st, ma, m, t) }
    private class Flags(val starting: Boolean, val media: MediaAccess, val mode: NewSessionMode?, val target: Trip?)

    val state: StateFlow<NewSessionUiState> = combine(form, repository.observeOpenSegment(), repository.observeActiveTrip(), flags) { f, open, trip, fl ->
        NewSessionUiState(f, open, fl.starting, fl.media, fl.mode, fl.target, trip)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewSessionUiState())

    fun requiredPermissions() = controller.requiredPermissions()

    fun mediaPermissionSet(): Array<String> = mediaPermissions.requestSet()

    fun refreshMediaAccess() {
        mediaAccess.value = mediaPermissions.access()
    }

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
                    tripId = targetTrip.value?.id,
                    newTrip = mode.value == NewSessionMode.NEW_TRIP,
                )
                _events.send(NewSessionEvent.Started(id))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("Bitacora", "start session failed: ${e.javaClass.simpleName} ${e.message}")
                _events.send(NewSessionEvent.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                starting.value = false
            }
        }
    }
}
