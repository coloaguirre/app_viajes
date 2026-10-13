package com.bitacoraviaje.app.ui.camera

import android.location.Location
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.camera.CameraCapture
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
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
    val error: String? = null,
    /** True when the last shot had no recent fix: it will be linked by time by the sweep. */
    val lastWithoutFix: Boolean = false,
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val capture: CameraCapture,
    private val tracking: com.bitacoraviaje.data.repository.TrackingRepository,
) : ViewModel() {
    val segmentId: String = savedState.toRoute<Route.Camera>().segmentId
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    /** MediaStore values for a new shot of this stage (folder per trip title, name with the local time) and the stage's zone. */
    suspend fun contentValues(kind: MediaKind, nowUtcMs: Long, @Suppress("UNUSED_PARAMETER") unused: String?): Pair<android.content.ContentValues, String> {
        val segment = tracking.getSegment(segmentId)
        val trip = segment?.let { tracking.getTrip(it.tripId) }
        val tz = segment?.tzId ?: "UTC"
        return capture.contentValues(kind, trip?.title, nowUtcMs, tz) to tz
    }

    /** The fix for the file's GPS tags, fetched right before the shutter (DISENO §7.8). */
    suspend fun location(): Location? = capture.location()

    fun onSaved(uri: Uri, kind: MediaKind, location: Location?, takenUtcMs: Long) {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val item = runCatching { capture.register(uri, kind, segmentId, location, takenUtcMs) }.getOrNull()
            _state.update { it.copy(saving = false, lastShot = item ?: it.lastShot, lastWithoutFix = item != null && location == null, error = if (item == null) "register" else null) }
        }
    }

    fun onError(message: String) {
        _state.update { it.copy(saving = false, error = message) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
