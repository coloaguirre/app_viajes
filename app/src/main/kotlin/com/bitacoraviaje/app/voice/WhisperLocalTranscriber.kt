package com.bitacoraviaje.app.voice

import android.content.Context
import android.util.Log
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.voice.models.ModelStore
import com.bitacoraviaje.app.voice.models.WhisperModel
import com.bitacoraviaje.whisper.WhisperContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * whisper.cpp on the device (DISENO §8.3, default engine). Available once the chosen
 * model and the VAD model are downloaded; nothing leaves the phone. The context is
 * loaded per call (a few hundred ms for `base`) so memory is not held between notes.
 */
class WhisperLocalTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
) : Transcriber {
    override val id: String = "whisper-local"
    private val store = ModelStore(File(context.filesDir, "models"))

    private suspend fun model(): WhisperModel = WhisperModel.byId(prefs.settings.first().transcriptionModel)

    override suspend fun isAvailable(): Boolean = store.isInstalled(model()) && store.isVadInstalled()

    override suspend fun transcribe(audio: File, languageTag: String): TranscriptResult {
        val m = model()
        val pcm = withContext(Dispatchers.Default) { AudioDecoder.decodeToPcm16Mono16k(audio) }
        val samples = pcm16ToFloat(pcm)
        val lang = whisperLanguage(languageTag)
        val t0 = System.nanoTime()
        val result = WhisperContext.load(store.file(m)).use { ctx -> ctx.transcribe(samples, lang, vadModelPath = store.vadFile().absolutePath) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val kept = result.segments.filter { it.noSpeechProb < NO_SPEECH_MAX }
        val text = kept.joinToString(" ") { it.text }.trim()
        val meanConf = if (kept.isEmpty()) 0f else kept.map { it.confidence }.average().toFloat()
        Log.i(TAG, "whisper ${m.id} audio=${samples.size / 16}ms took=${ms}ms segments=${result.segments.size} kept=${kept.size} conf=${"%.2f".format(meanConf)} lang=${result.language}")
        return TranscriptResult(text, kept.map { TranscriptSegment(it.startMs, it.endMs, it.text, it.confidence) }, result.language ?: lang.takeIf { it != "auto" })
    }

    companion object {
        private const val TAG = "Bitacora"

        /** Segments the decoder itself thinks are not speech are dropped (hallucination guard on top of VAD). */
        const val NO_SPEECH_MAX = 0.6f

        /** "es-AR" → "es"; "auto" stays; anything else → its language subtag. */
        fun whisperLanguage(tag: String): String = if (tag.equals("auto", ignoreCase = true)) "auto" else tag.substringBefore('-').lowercase().ifBlank { "es" }

        fun pcm16ToFloat(pcm: ByteArray): FloatArray {
            val n = pcm.size / 2
            val out = FloatArray(n)
            for (i in 0 until n) {
                val lo = pcm[2 * i].toInt() and 0xff
                val hi = pcm[2 * i + 1].toInt()
                out[i] = ((hi shl 8) or lo) / 32768f
            }
            return out
        }
    }
}
