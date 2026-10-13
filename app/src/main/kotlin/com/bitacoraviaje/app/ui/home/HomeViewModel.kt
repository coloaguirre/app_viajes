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
    /** A trip was removed from the list; the snackbar offers to undo for a few seconds (backlog D1). */
    data class TripDeleted(val title: String) : HomeEvent
    data object TripInUse : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: TrackingRepository,
    private val controller: TrackingController,
    stateHolder: TrackingStateHolder,
    private val exportLoader: com.bitacoraviaje.app.export.ExportLoader,
) : ViewModel() {
    /** Trip waiting for its "Deshacer" window to pass before the rows are really removed. */
    private val pendingDelete = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private var pendingJob: kotlinx.coroutines.Job? = null
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
        val visible = trips.filter { it.id != hidden }
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
     * Backlog D1: the row disappears at once, the data goes after [UNDO_MS] (10 s) unless [undoDelete] is called.
     * Before deleting, the trip's backup is rewritten (never lose data), the audio notes and the saved video
     * plan go with it; photos and videos in the gallery are never touched. A trip with an open segment is refused.
     */
    fun deleteTrip(tripId: String) {
        viewModelScope.launch {
            val trip = repository.getTrip(tripId) ?: return@launch
            if (repository.getOpenSegment()?.tripId == tripId) { _events.send(HomeEvent.TripInUse); return@launch }
            pendingJob?.cancel()
            pendingDelete.value = tripId
            _events.send(HomeEvent.TripDeleted(trip.title))
            pendingJob = viewModelScope.launch {
                kotlinx.coroutines.delay(UNDO_MS)
                runCatching { com.bitacoraviaje.app.export.BackupFiles.write(appContext, exportLoader, tripId) }
                    .onFailure { android.util.Log.w("Bitacora", "backup before delete failed: ${it.javaClass.simpleName}") }
                if (repository.deleteTrip(tripId)) {
                    java.io.File(appContext.filesDir, "audio/$tripId").deleteRecursively()
                    runCatching { com.bitacoraviaje.app.video.VideoPlanStore(appContext).clear(tripId) }
                    android.util.Log.i("Bitacora", "trip deleted id=$tripId")
                }
                pendingDelete.value = null
            }
        }
    }

    fun undoDelete() {
        pendingJob?.cancel()
        pendingJob = null
        pendingDelete.value = null
    }

    override fun onCleared() {
        // Leaving Home before the window ends must not lose the deletion the user asked for: run it now.
        pendingDelete.value?.let { id ->
            pendingJob?.cancel()
            kotlinx.coroutines.runBlocking { runCatching { com.bitacoraviaje.app.export.BackupFiles.write(appContext, exportLoader, id) }; repository.deleteTrip(id) }
        }
        super.onCleared()
    }

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private companion object { /** Matches SnackbarDuration.Long, so "Deshacer" is available for as long as it is visible. */
        const val UNDO_MS = 10_000L }

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
