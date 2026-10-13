package com.bitacoraviaje.app.ui.home

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitacoraviaje.app.tracking.TrackingController
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    /** Trips not archived and not pending deletion, newest first. */
    val trips: List<Trip> = emptyList(),
    /** Archived trips (backlog D1), shown folded at the end. */
    val archived: List<Trip> = emptyList(),
    val openSegment: Segment? = null,
    /** Open segment in the database but no service running: process was killed or phone rebooted. */
    val needsRecovery: Boolean = false,
)

sealed interface HomeEvent {
    data class OpenActiveSession(val segmentId: String) : HomeEvent
    data object PermissionDenied : HomeEvent
    /** A trip left the list; the snackbar offers to undo and, when it goes away, the screen commits the deletion (backlog D1). */
    data class TripDeleted(val tripId: String, val title: String) : HomeEvent
    data object TripInUse : HomeEvent
    data class TripDeleteFailed(val title: String) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: TrackingRepository,
    private val controller: TrackingController,
    stateHolder: TrackingStateHolder,
) : ViewModel() {
    /** Last known titles of hidden trips, for the failure message once the row is gone. */
    private val titles = HashMap<String, String>()
    /**
     * Trips the user asked to delete whose rows are hidden: waiting for the "Deshacer" window, or already
     * handed to [com.bitacoraviaje.app.home.DeleteTripWorker]. They reappear only on undo or on a failed job.
     */
    private val pendingDelete = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    private val workManager = androidx.work.WorkManager.getInstance(appContext)
    /** Import job (PLAN 3.3) as the Home dialog sees it. */
    val importStatus: StateFlow<com.bitacoraviaje.app.export.ImportStatus> = androidx.work.WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(com.bitacoraviaje.app.export.ImportWorker.WORK_NAME)
        .map { infos -> com.bitacoraviaje.app.export.ImportStatus.from(infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.bitacoraviaje.app.export.ImportStatus())

    fun importFrom(uri: android.net.Uri, conflict: com.bitacoraviaje.app.export.ImportConflict = com.bitacoraviaje.app.export.ImportConflict.ASK) {
        com.bitacoraviaje.app.export.ImportWorker.enqueue(appContext, uri, conflict)
    }

    /** Answer to the "trip already exists" question: the uri travels in the failed work's output, so it survives process death (review M5). */
    fun resolveImportConflict(conflict: com.bitacoraviaje.app.export.ImportConflict) {
        importStatus.value.conflictUri?.let { importFrom(it.toUri(), conflict) }
    }

    fun dismissImport() = androidx.work.WorkManager.getInstance(appContext).pruneWork()

    val state: StateFlow<HomeUiState> = combine(repository.observeTrips(), repository.observeOpenSegment(), stateHolder.state, pendingDelete) { trips, open, live, hidden ->
        val visible = trips.filter { it.id !in hidden }
        HomeUiState(visible.filter { !it.archived }, visible.filter { it.archived }, open, needsRecovery = open != null && !live.isRunning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun renameTrip(tripId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.renameTrip(tripId, title) }
    }

    fun setArchived(tripId: String, archived: Boolean) {
        viewModelScope.launch { repository.setTripArchived(tripId, archived) }
    }

    /**
     * Backlog D1: the row disappears at once and the snackbar offers "Deshacer"; when the snackbar goes away
     * the screen calls [commitDelete], which hands the trip to [com.bitacoraviaje.app.home.DeleteTripWorker]
     * (backup first, then delete; see [com.bitacoraviaje.app.home.TripDeleter]). A trip with a recording
     * segment is refused up front. Several trips can be pending at once, each with its own snackbar.
     */
    fun deleteTrip(tripId: String) {
        viewModelScope.launch {
            val trip = repository.getTrip(tripId) ?: return@launch
            if (repository.getOpenSegment()?.tripId == tripId) { _events.send(HomeEvent.TripInUse); return@launch }
            titles[tripId] = trip.title
            pendingDelete.value += tripId
            _events.send(HomeEvent.TripDeleted(tripId, trip.title))
        }
    }

    fun undoDelete(tripId: String) {
        pendingDelete.value -= tripId
    }

    /** The undo window closed: enqueue the job. The row stays hidden until the job reports. */
    fun commitDelete(tripId: String) {
        if (tripId !in pendingDelete.value) return
        com.bitacoraviaje.app.home.DeleteTripWorker.enqueue(appContext, tripId)
    }

    /** Outcome of every delete job: a success unhides nothing (the row is gone), a failure brings the trip back with a message. */
    private val deleteOutcomes = workManager.getWorkInfosByTagFlow(com.bitacoraviaje.app.home.DeleteTripWorker.TAG_WORK)
        .onEach { infos ->
            infos.filter { it.state.isFinished }.forEach { info ->
                val id = info.outputData.getString(com.bitacoraviaje.app.home.DeleteTripWorker.KEY_TRIP_ID) ?: return@forEach
                if (id !in pendingDelete.value) return@forEach
                pendingDelete.value -= id
                if (info.state == androidx.work.WorkInfo.State.FAILED) {
                    val outcome = info.outputData.getString(com.bitacoraviaje.app.home.DeleteTripWorker.KEY_OUTCOME)
                    val title = titles[id] ?: repository.getTrip(id)?.title ?: ""
                    _events.send(if (outcome == com.bitacoraviaje.app.home.TripDeleter.Outcome.IN_USE.name) HomeEvent.TripInUse else HomeEvent.TripDeleteFailed(title))
                }
                workManager.pruneWork()
            }
        }
        .launchIn(viewModelScope)

    override fun onCleared() {
        // Leaving Home with a snackbar still open: the user asked for the deletion, so it is committed, not forgotten.
        pendingDelete.value.forEach { com.bitacoraviaje.app.home.DeleteTripWorker.enqueue(appContext, it) }
        super.onCleared()
    }

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()


    fun requiredPermissions() = controller.requiredPermissions()

    /** Recovery dialog: keep recording the open segment. */
    fun resumeOpenSegment() {
        viewModelScope.launch {
            val open = repository.getOpenSegment() ?: return@launch
            if (!controller.hasLocationPermission()) {
                _events.send(HomeEvent.PermissionDenied)
                return@launch
            }
            controller.resumeOpenSegment(open)
            _events.send(HomeEvent.OpenActiveSession(open.id))
        }
    }

    /** Recovery dialog: close the open segment as RECOVERED (stats computed from what was saved). */
    fun closeOpenSegmentAsRecovered() {
        viewModelScope.launch {
            val open = repository.getOpenSegment() ?: return@launch
            controller.closeOpenSegmentAsRecovered(open.id)
        }
    }

}
