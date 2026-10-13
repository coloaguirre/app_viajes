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
import kotlinx.coroutines.flow.first

/**
 * `transcribe-<noteId>`, KEEP (DISENO §8.3). Engine order: whisper-local (Phase 3) →
 * android-speech → cloud (only if enabled) → stub (FAILED, user can retry).
 */
@HiltWorker
class TranscribeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notes: NotesRepository,
    private val whisper: WhisperLocalTranscriber,
    private val speech: AndroidSpeechTranscriber,
    private val prefs: com.bitacoraviaje.app.settings.UserPreferences,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val lang = inputData.getString(KEY_LANG) ?: "es-AR"
        val note = notes.getVoiceNote(id) ?: return Result.failure()
        val file = File(applicationContext.filesDir, note.audioPath)
        if (!file.exists()) return Result.failure()
        notes.setTranscriptStatus(id, TranscriptStatus.RUNNING)
        // Engine order (PROGRESS 2026-10-13): measured on real notes the system's on-device recognizer
        // beat whisper `base`, so "auto" tries it first and whisper covers phones without a language pack.
        val engines: List<Transcriber> = engineOrder(prefs.settings.first().transcriptionEngine, whisper, speech)
        var packPending = false
        for (engine in engines) {
            if (!engine.isAvailable()) continue
            try {
                val r = engine.transcribe(file, lang)
                if (r.text.isBlank()) throw TranscriptionException("empty transcript from ${engine.id}")
                notes.setTranscript(id, engine.id, r.language, r.text, segmentsJson(r.segments))
                Log.i(TAG, "transcribed note=$id engine=${engine.id} chars=${r.text.length}")
                return Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // WorkManager stop/replace: not a failure of the engine (review M4)
            } catch (e: LanguagePackPendingException) {
                packPending = true
                Log.i(TAG, "engine ${engine.id} waiting for a language pack for note=$id: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "engine ${engine.id} failed for note=$id: ${e.message}")
            }
        }
        // FAILED only when WorkManager will not try again; otherwise the detail would offer a
        // manual retry while an automatic one is already scheduled (review M1). A language pack
        // download takes minutes and may wait for Wi-Fi: it gets a longer leash (review I1).
        val willRetry = if (packPending) runAttemptCount < MAX_ATTEMPTS_PACK_PENDING else runAttemptCount < 2
        notes.setTranscriptStatus(id, if (willRetry) TranscriptStatus.PENDING else TranscriptStatus.FAILED)
        return if (willRetry) Result.retry() else Result.failure()
    }

    private fun segmentsJson(segments: List<TranscriptSegment>): String? =
        if (segments.isEmpty()) null else segments.joinToString(prefix = "[", postfix = "]") { "{\"startMs\":${it.startMs},\"endMs\":${it.endMs},\"text\":${jsonString(it.text)}${it.confidence?.let { c -> ",\"confidence\":${"%.3f".format(java.util.Locale.ROOT, c)}" } ?: ""}}" }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Pure: which engines to try, in order, for a stored preference. */
        fun engineOrder(preference: String, whisper: Transcriber, speech: Transcriber): List<Transcriber> = when (preference) {
            "whisper" -> listOf(whisper, speech, StubTranscriber())
            "system" -> listOf(speech, StubTranscriber())
            else -> listOf(speech, whisper, StubTranscriber())
        }

        /** With exponential backoff from 30 s this covers roughly 15-20 min of download. */
        const val MAX_ATTEMPTS_PACK_PENDING = 10
        const val TAG = "Bitacora"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_LANG = "lang"

        fun enqueue(context: Context, noteId: String, languageTag: String, replace: Boolean = false, onlyWhenCharging: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(Data.Builder().putString(KEY_NOTE_ID, noteId).putString(KEY_LANG, languageTag).build())
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(onlyWhenCharging).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("transcribe-$noteId", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
