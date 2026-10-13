package com.bitacoraviaje.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionSupportCallback
import android.speech.RecognitionSupport
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
        val pcm = withContext(Dispatchers.Default) { AudioDecoder.decodeToPcm16Mono16k(audio) }
        val (readFd, writeFd) = ParcelFileDescriptor.createPipe()
        val writer = Thread {
            // The recognizer may close its end early (error 12 on devices without the
            // audio-source extra): the resulting EPIPE must not take the process down.
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { it.write(pcm) } }
                .onFailure { Log.w(TAG, "pcm feed ended early: ${it.message}") }
        }.apply { start() }
        return try {
            val lang = resolveOnDeviceLanguage(languageTag)
            withTimeout(RESULT_TIMEOUT_MS) { recognize(readFd, lang) }
        } finally {
            writer.interrupt()
            runCatching { readFd.close() }
        }
    }

    /**
     * The on-device engine only speaks the language packs the user downloaded (Pixel 8 Pro,
     * 2026-10-12: "es-AR" requested, no pack → error 12). Ask the engine what it has: use the
     * requested tag if installed, else any installed pack of the same language; if a pack is
     * supported but not installed, request its download and fail so the worker retries later.
     */
    private suspend fun resolveOnDeviceLanguage(requested: String): String {
        if (Build.VERSION.SDK_INT < 33) return requested
        val support = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<RecognitionSupport?> { cont ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, requested)
                cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
                recognizer.checkRecognitionSupport(
                    intent, context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            recognizer.destroy()
                            if (cont.isActive) cont.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            recognizer.destroy()
                            Log.w(TAG, "checkRecognitionSupport error $error")
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        } ?: return requested
        val installed = support.installedOnDeviceLanguages
        val supported = support.supportedOnDeviceLanguages
        val pending = support.pendingOnDeviceLanguages
        Log.i(TAG, "on-device speech packs installed=$installed pending=$pending supported=$supported")
        val language = requested.substringBefore('-').lowercase()
        fun sameLanguage(tag: String) = tag.substringBefore('-').lowercase() == language
        installed.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
        installed.firstOrNull(::sameLanguage)?.let {
            Log.i(TAG, "requested $requested not installed; using $it")
            return it
        }
        if (pending.any(::sameLanguage)) throw TranscriptionException("language pack for $language still downloading")
        val downloadable = supported.firstOrNull { it.equals(requested, ignoreCase = true) } ?: supported.firstOrNull(::sameLanguage)
        if (downloadable != null) {
            withContext(Dispatchers.Main) {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, downloadable))
                r.destroy()
            }
            Log.i(TAG, "requested download of on-device pack $downloadable")
            throw TranscriptionException("language pack $downloadable requested for download; retry later")
        }
        throw TranscriptionException("no on-device pack for language $language (installed=$installed)")
    }

    private suspend fun recognize(readFd: ParcelFileDescriptor, languageTag: String): TranscriptResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                cont.resumeWithException(TranscriptionException("no on-device recognizer"))
                return@suspendCancellableCoroutine
            }
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val collected = StringBuilder()
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (cont.isActive) cont.resumeWithException(TranscriptionException("speech recognizer error $error"))
                }

                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    texts?.firstOrNull()?.let { collected.append(it) }
                    recognizer.destroy()
                    if (cont.isActive) cont.resume(TranscriptResult(collected.toString().trim(), emptyList(), languageTag))
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                if (Build.VERSION.SDK_INT >= 33) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioDecoder.TARGET_RATE)
                }
            }
            cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
            Log.i(TAG, "speech recognizer started from file")
            recognizer.startListening(intent)
        }
    }

    private companion object {
        const val TAG = "Bitacora"
        const val RESULT_TIMEOUT_MS = 60_000L
    }
}
