package com.bitacoraviaje.app.ui.camera

import android.content.ContentValues
import android.location.Location
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.R
import com.bitacoraviaje.app.camera.CameraCapture
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraUiState(
    val lastShot: MediaItem? = null,
    val saving: Boolean = false,
    /** String resource of the error to show, or null. */
    val errorRes: Int? = null,
    /** Error code appended to [errorRes] when it takes one (video finalize). */
    val errorArg: Int? = null,
    /** True when the last shot had no recent fix: it will be linked by time by the sweep. */
    val lastWithoutFix: Boolean = false,
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val capture: CameraCapture,
    private val tracking: TrackingRepository,
) : ViewModel() {
    val segmentId: String = savedState.toRoute<Route.Camera>().segmentId
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()
    private val pending = HashSet<Uri>()

    init {
        // Registration runs in the application scope; this route only listens while it exists (review I3).
        viewModelScope.launch {
            capture.registered.collect { r ->
                if (!pending.remove(r.uri)) return@collect
                _state.update { it.copy(saving = pending.isNotEmpty(), lastShot = r.item ?: it.lastShot, lastWithoutFix = r.item != null && r.withoutFix, errorRes = if (r.item == null) R.string.camera_error_register else null) }
            }
        }
    }

    /** MediaStore values for a new shot of this stage (folder per trip title, name with the local time). */
    suspend fun contentValues(kind: MediaKind, nowUtcMs: Long): ContentValues {
        val segment = tracking.getSegment(segmentId)
        val trip = segment?.let { tracking.getTrip(it.tripId) }
        return capture.contentValues(kind, trip?.title, nowUtcMs, segment?.tzId ?: "UTC")
    }

    /** The fix for the file's GPS tags, fetched right before the shutter (DISENO §7.8). */
    suspend fun location(): Location? = capture.location()

    fun onSaved(uri: Uri, kind: MediaKind, location: Location?, takenUtcMs: Long) {
        pending += uri
        _state.update { it.copy(saving = true, errorRes = null) }
        capture.enqueue(uri, kind, segmentId, location, takenUtcMs)
    }

    fun onError(res: Int, arg: Int? = null) = _state.update { it.copy(saving = false, errorRes = res, errorArg = arg) }

    fun clearError() = _state.update { it.copy(errorRes = null, errorArg = null) }
}
