package com.bitacoraviaje.app.ui.voicenote

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitacoraviaje.app.map.MapMarker
import com.bitacoraviaje.app.map.MapPoint
import com.bitacoraviaje.app.map.MarkerKind
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.ui.navigation.Route
import com.bitacoraviaje.app.voice.TranscribeWorker
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.data.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VoiceNoteDetailUiState(
    val note: VoiceNote? = null,
    val isPlaying: Boolean = false,
    val map: TrackMapState = TrackMapState(),
)

@HiltViewModel
class VoiceNoteDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val notes: NotesRepository,
    private val prefs: UserPreferences,
) : ViewModel() {
    val noteId: String = savedState.toRoute<Route.VoiceNoteDetail>().noteId
    private val playing = MutableStateFlow(false)
    private var player: MediaPlayer? = null
    private var current: VoiceNote? = null

    val state: StateFlow<VoiceNoteDetailUiState> = combine(notes.observeVoiceNote(noteId), playing) { n, p ->
        current = n
        val point = n?.lat?.let { la -> n.lon?.let { lo -> MapPoint(la, lo) } }
        VoiceNoteDetailUiState(n, p, TrackMapState(markers = listOfNotNull(point?.let { MapMarker(noteId, it, MarkerKind.VOICE) }), follow = false, focus = point))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceNoteDetailUiState())

    fun togglePlay() {
        val n = current ?: return
        val p = player
        if (p != null && p.isPlaying) {
            p.pause()
            playing.value = false
            return
        }
        if (p == null) {
            val file = File(context.filesDir, n.audioPath)
            if (!file.exists()) return
            val mp = MediaPlayer()
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { playing.value = false }
                mp.prepare()
            } catch (e: Exception) {
                // Corrupt or truncated file: do not crash the screen, do not leak the player (review M2).
                android.util.Log.w("Bitacora", "voice note playback failed: ${e.javaClass.simpleName}")
                mp.release()
                return
            }
            player = mp
        }
        player?.start()
        playing.value = true
    }

    fun saveEdit(text: String) {
        viewModelScope.launch {
            notes.editTranscript(noteId, text)
        }
    }

    fun retryTranscription() {
        viewModelScope.launch {
            notes.setTranscriptStatus(noteId, TranscriptStatus.PENDING)
            val s = prefs.settings.first()
            TranscribeWorker.enqueue(context, noteId, s.transcriptionLanguage, replace = true, onlyWhenCharging = s.transcribeOnlyCharging)
        }
    }

    override fun onCleared() {
        player?.release()
        player = null
    }
}
