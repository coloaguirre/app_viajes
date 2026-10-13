package com.bitacoraviaje.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Looper
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The system recognizer fed from a file (`EXTRA_AUDIO_SOURCE`, API 33+; researcher G1).
 * Trap: a cloud/network engine may ignore the audio-source extra and silently open the
 * microphone instead. Only the on-device recognizer is used (review I3); where there is
 * none the engine reports itself unavailable and the note waits for whisper (Fase 3).
 */
class AndroidSpeechTranscriber @Inject constructor(@ApplicationContext private val context: Context) : Transcriber {
    override val id: String = "android-speech"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Main) {
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    override suspend fun transcribe(audio: File, languageTag: String): TranscriptResult {
        if (Build.VERSION.SDK_INT < 33) throw TranscriptionException("file recognition needs API 33")
        // Language first: no point decoding and opening the pipe when there is no pack (review M2).
        val lang = kotlinx.coroutines.withTimeoutOrNull(SUPPORT_TIMEOUT_MS) { resolveOnDeviceLanguage(languageTag) } ?: languageTag
        val pcm = withContext(Dispatchers.Default) { AudioDecoder.decodeToPcm16Mono16k(audio) }
        val (readFd, writeFd) = ParcelFileDescriptor.createPipe()
        if (com.bitacoraviaje.app.BuildConfig.DEBUG) {
            // Debug aid: the exact bytes fed to the engine, next to the note (pull with run-as).
            runCatching { File(audio.parentFile, audio.nameWithoutExtension + ".pcm").writeBytes(pcm) }
        }
        val writer = Thread {
            // Fed at real-time pace in 100 ms chunks: the on-device engine's endpointer treats a
            // stream that arrives all at once as "no speech" (Pixel 8 Pro: error 7 NO_MATCH).
            // The recognizer may also close its end early (ERROR_LANGUAGE_NOT_SUPPORTED = 12,
            // ERROR_LANGUAGE_UNAVAILABLE = 13): the resulting EPIPE must not take the process down.
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { out ->
                    var off = 0
                    val start = System.nanoTime()
                    while (off < pcm.size && !Thread.currentThread().isInterrupted) {
                        val n = minOf(CHUNK_BYTES, pcm.size - off)
                        out.write(pcm, off, n)
                        off += n
                        val due = start + (off.toLong() * 1_000_000_000L / BYTES_PER_SECOND)
                        val wait = (due - System.nanoTime()) / 1_000_000
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
            }.onFailure { Log.w(TAG, "pcm feed ended early: ${it.message}") }
        }.apply { start() }
        return try {
            withTimeout(RESULT_TIMEOUT_MS) { recognize(readFd, lang) }
        } finally {
            writer.interrupt()
            runCatching { readFd.close() }
        }
    }

    /**
     * The on-device engine only speaks the language packs the user downloaded. Pixel 8 Pro,
     * 2026-10-12: "es-AR" requested → error 12 (ERROR_LANGUAGE_NOT_SUPPORTED: the tag itself
     * is unknown to the engine) while es-US and es-ES were installed. Ask the engine what it
     * has and decide with [OnDeviceLanguages.pick]; a missing-but-supported pack is requested
     * for download on the same connected instance (review B1) and the note stays pending.
     */
    private suspend fun resolveOnDeviceLanguage(requested: String): String {
        if (Build.VERSION.SDK_INT < 33) return requested
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    cont.resumeWithException(TranscriptionException("no on-device recognizer"))
                    return@suspendCancellableCoroutine
                }
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val mainHandler = Handler(Looper.getMainLooper())
                fun destroyLater() = mainHandler.post { runCatching { recognizer.destroy() } }
                cont.invokeOnCancellation { destroyLater() }
                val probe = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, requested)
                recognizer.checkRecognitionSupport(
                    probe, context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            val installed = recognitionSupport.installedOnDeviceLanguages
                            val pending = recognitionSupport.pendingOnDeviceLanguages
                            val supported = recognitionSupport.supportedOnDeviceLanguages
                            Log.i(TAG, "on-device speech packs installed=$installed pending=$pending")
                            Log.d(TAG, "on-device speech packs supported=$supported")
                            when (val d = OnDeviceLanguages.pick(requested, installed, pending, supported)) {
                                is LanguageDecision.Use -> {
                                    if (!d.tag.equals(requested, ignoreCase = true)) Log.i(TAG, "requested $requested not installed; using ${d.tag}")
                                    destroyLater()
                                    if (cont.isActive) cont.resume(d.tag)
                                }
                                LanguageDecision.Pending -> {
                                    destroyLater()
                                    if (cont.isActive) cont.resumeWithException(LanguagePackPendingException("language pack still downloading"))
                                }
                                is LanguageDecision.Download -> {
                                    requestDownload(recognizer, d.tag, ::destroyLater)
                                    if (cont.isActive) cont.resumeWithException(LanguagePackPendingException("language pack ${d.tag} requested for download"))
                                }
                                LanguageDecision.Unsupported -> {
                                    destroyLater()
                                    if (cont.isActive) cont.resumeWithException(TranscriptionException("no on-device pack for ${requested.substringBefore('-')} (installed=$installed)"))
                                }
                            }
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "checkRecognitionSupport error $error; trying $requested as is")
                            destroyLater()
                            if (cont.isActive) cont.resume(requested)
                        }
                    },
                )
            }
        }
    }

    /**
     * The download request is a message on the recognizer's queue: destroying the instance
     * right away would drop it (review B1). API 34+ reports back through a listener; API 33
     * only lets us post the destroy behind the request on the same looper.
     */
    @androidx.annotation.RequiresApi(33)
    private fun requestDownload(recognizer: SpeechRecognizer, tag: String, destroyLater: () -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        if (Build.VERSION.SDK_INT >= 34) {
            recognizer.triggerModelDownload(
                intent, context.mainExecutor,
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = Unit
                    override fun onSuccess() {
                        Log.i(TAG, "on-device pack $tag installed")
                        destroyLater()
                    }
                    override fun onScheduled() {
                        Log.i(TAG, "on-device pack $tag download scheduled")
                    }
                    override fun onError(error: Int) {
                        Log.w(TAG, "on-device pack $tag download error $error")
                        destroyLater()
                    }
                },
            )
        } else {
            recognizer.triggerModelDownload(intent)
            destroyLater()
        }
        Log.i(TAG, "requested download of on-device pack $tag")
    }

    private suspend fun recognize(readFd: ParcelFileDescriptor, languageTag: String): TranscriptResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                cont.resumeWithException(TranscriptionException("no on-device recognizer"))
                return@suspendCancellableCoroutine
            }
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val collected = StringBuilder()
            var lastPartial: String? = null
            var segments = 0
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    // Some engines deliver the text as partials and an empty final: keep the last one.
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (cont.isActive) cont.resumeWithException(TranscriptionException("speech recognizer error $error"))
                }

                // Segmented session (API 33, file source): one final result per utterance arrives
                // here; without it the engine still finalizes each utterance internally but the
                // single onResults at the end comes back empty (Pixel 8 Pro, 2026-10-13: three
                // "handleFinalResult" in the engine log, count=0 in onResults).
                override fun onSegmentResults(segmentResults: Bundle) {
                    val text = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    segments += 1
                    if (text.isNotBlank()) {
                        if (collected.isNotEmpty()) collected.append(' ')
                        collected.append(text)
                    }
                    lastPartial = null
                }

                override fun onEndOfSegmentedSession() = finish(fromSegments = true)

                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
                        if (collected.isNotEmpty()) collected.append(' ')
                        collected.append(it)
                    }
                    finish(fromSegments = false)
                }

                private fun finish(fromSegments: Boolean) {
                    if (collected.isBlank()) lastPartial?.let { collected.append(it) }
                    Log.i(TAG, "speech done segments=$segments segmented=$fromSegments chars=${collected.length}")
                    recognizer.destroy()
                    if (!cont.isActive) return
                    val text = collected.toString().trim()
                    // An empty result is not a transcription: the note must stay retryable.
                    if (text.isBlank()) cont.resumeWithException(TranscriptionException("recognizer returned no text")) else cont.resume(TranscriptResult(text, emptyList(), languageTag))
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (Build.VERSION.SDK_INT >= 33) {
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioDecoder.TARGET_RATE)
                }
            }
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { runCatching { recognizer.destroy() } } }
            Log.i(TAG, "speech recognizer started from file")
            recognizer.startListening(intent)
        }
    }

    private companion object {
        const val TAG = "Bitacora"
        const val RESULT_TIMEOUT_MS = 6 * 60_000L // notes are capped at 5 min and fed in real time
        const val SUPPORT_TIMEOUT_MS = 10_000L // engine bind that never answers: fall back to the requested tag (review I2)
        const val BYTES_PER_SECOND = AudioDecoder.TARGET_RATE * 2
        const val CHUNK_BYTES = BYTES_PER_SECOND / 10
    }
}
