package com.bitacoraviaje.app.voice

import android.content.Context
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.tracking.TrackingStateHolder
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Position + time for a note (DISENO §8.1): last fix if fresh, else a quick current-location request. */
data class NotePosition(val lat: Double?, val lon: Double?, val accuracyM: Float?)

@Singleton
class NotesController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracking: TrackingRepository,
    private val notes: NotesRepository,
    private val stateHolder: TrackingStateHolder,
    private val fused: FusedLocationProviderClient,
    private val prefs: UserPreferences,
) {
    suspend fun currentSegmentId(): String? = stateHolder.state.value.segmentId ?: tracking.getOpenSegment()?.id

    suspend fun position(nowUtcMs: Long = System.currentTimeMillis()): NotePosition {
        val last = stateHolder.state.value.lastPoint
        if (last != null && nowUtcMs - last.timeUtcMs <= FRESH_FIX_MS) return NotePosition(last.lat, last.lon, last.hAccuracyM)
        val loc = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) { currentLocation() }
        return if (loc != null) NotePosition(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null) else NotePosition(last?.lat, last?.lon, last?.hAccuracyM)
    }

    @Suppress("MissingPermission") // location permission is a precondition of recording
    private suspend fun currentLocation(): android.location.Location? = suspendCancellableCoroutine { cont ->
        val cts = CancellationTokenSource()
        cont.invokeOnCancellation { cts.cancel() }
        val req = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS).build()
        try {
            fused.getCurrentLocation(req, cts.token)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } catch (e: SecurityException) {
            // Permission revoked after the segment was opened: the note is saved without a position (review M3).
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Persists a finished recording and queues its transcription. */
    suspend fun saveVoiceNote(segmentId: String, tripId: String, noteId: String, startedUtcMs: Long, durationMs: Long, pos: NotePosition, mediaItemId: String? = null): VoiceNote {
        val note = VoiceNote(
            id = noteId, segmentId = segmentId, startedUtcMs = startedUtcMs, durationMs = durationMs,
            lat = pos.lat, lon = pos.lon, hAccuracyM = pos.accuracyM, audioPath = VoiceRecorder.relativePath(tripId, noteId), mediaItemId = mediaItemId,
        )
        notes.addVoiceNote(note)
        val s = prefs.settings.first()
        TranscribeWorker.enqueue(context, noteId, s.transcriptionLanguage, onlyWhenCharging = s.transcribeOnlyCharging)
        return note
    }

    suspend fun addMarker(text: String?): TextNote? = addText(TextNoteKind.MARKER, text)

    suspend fun addHighlight(): TextNote? = addText(TextNoteKind.HIGHLIGHT, null)

    private suspend fun addText(kind: TextNoteKind, text: String?): TextNote? {
        val segmentId = currentSegmentId() ?: return null
        val pos = position()
        return notes.addTextNote(segmentId, kind, System.currentTimeMillis(), pos.lat, pos.lon, text)
    }

    fun newNoteId(): String = UUID.randomUUID().toString()

    companion object {
        const val FRESH_FIX_MS = 30_000L
        const val CURRENT_LOCATION_TIMEOUT_MS = 5_000L
    }
}
