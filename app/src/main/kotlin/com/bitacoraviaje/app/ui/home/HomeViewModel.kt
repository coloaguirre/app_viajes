package com.bitacoraviaje.app.ui.home

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
    val trips: List<Trip> = emptyList(),
    val openSegment: Segment? = null,
    /** Open segment in the database but no service running: process was killed or phone rebooted. */
    val needsRecovery: Boolean = false,
)

sealed interface HomeEvent {
    data class OpenActiveSession(val segmentId: String) : HomeEvent
    data object PermissionDenied : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: TrackingRepository,
    private val controller: TrackingController,
    stateHolder: TrackingStateHolder,
) : ViewModel() {
    /** Import job (PLAN 3.3) as the Home dialog sees it. */
    val importStatus: StateFlow<com.bitacoraviaje.app.export.ImportStatus> = androidx.work.WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(com.bitacoraviaje.app.export.ImportWorker.WORK_NAME)
        .map { infos -> com.bitacoraviaje.app.export.ImportStatus.from(infos.firstOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.bitacoraviaje.app.export.ImportStatus())

    private var lastImportUri: android.net.Uri? = null

    fun importFrom(uri: android.net.Uri, conflict: com.bitacoraviaje.app.export.ImportConflict = com.bitacoraviaje.app.export.ImportConflict.ASK) {
        lastImportUri = uri
        com.bitacoraviaje.app.export.ImportWorker.enqueue(appContext, uri, conflict)
    }

    /** Answer to the "trip already exists" question: re-run on the same file with the chosen policy. */
    fun resolveImportConflict(conflict: com.bitacoraviaje.app.export.ImportConflict) {
        lastImportUri?.let { importFrom(it, conflict) }
    }

    fun dismissImport() = androidx.work.WorkManager.getInstance(appContext).pruneWork()

    val state: StateFlow<HomeUiState> = combine(repository.observeTrips(), repository.observeOpenSegment(), stateHolder.state) { trips, open, live ->
        HomeUiState(trips, open, needsRecovery = open != null && !live.isRunning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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
