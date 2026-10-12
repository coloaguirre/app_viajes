package com.bitacoraviaje.app.voice

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.data.repository.NotesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `transcribe-<noteId>`, KEEP (DISENO §8.3). Engine order: whisper-local (Phase 3) →
 * android-speech → cloud (only if enabled) → stub (FAILED, user can retry).
 */
@HiltWorker
class TranscribeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notes: NotesRepository,
    private val speech: AndroidSpeechTranscriber,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val lang = inputData.getString(KEY_LANG) ?: "es-AR"
        val note = notes.getVoiceNote(id) ?: return Result.failure()
        val file = File(applicationContext.filesDir, note.audioPath)
        if (!file.exists()) return Result.failure()
        notes.setTranscriptStatus(id, TranscriptStatus.RUNNING)
        val engines: List<Transcriber> = listOf(speech, StubTranscriber())
        for (engine in engines) {
            if (!engine.isAvailable()) continue
            try {
                val r = engine.transcribe(file, lang)
                notes.setTranscript(id, engine.id, r.language, r.text, segmentsJson(r.segments))
                Log.i(TAG, "transcribed note=$id engine=${engine.id} chars=${r.text.length}")
                return Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "engine ${engine.id} failed for note=$id: ${e.message}")
            }
        }
        notes.setTranscriptStatus(id, TranscriptStatus.FAILED)
        return if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    private fun segmentsJson(segments: List<TranscriptSegment>): String? =
        if (segments.isEmpty()) null else segments.joinToString(prefix = "[", postfix = "]") { "{\"startMs\":${it.startMs},\"endMs\":${it.endMs},\"text\":${jsonString(it.text)}}" }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val TAG = "Bitacora"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_LANG = "lang"

        fun enqueue(context: Context, noteId: String, languageTag: String) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(Data.Builder().putString(KEY_NOTE_ID, noteId).putString(KEY_LANG, languageTag).build())
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("transcribe-$noteId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
