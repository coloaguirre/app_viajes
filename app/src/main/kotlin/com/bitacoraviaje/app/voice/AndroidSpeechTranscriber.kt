package com.bitacoraviaje.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
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
 * Trap: on devices that do not honour the audio source it silently opens the mic
 * instead, so the result is only trusted when it arrives before [RESULT_TIMEOUT_MS]
 * and the engine reported on-device support.
 */
class AndroidSpeechTranscriber @Inject constructor(@ApplicationContext private val context: Context) : Transcriber {
    override val id: String = "android-speech"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Main) {
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context)
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
            withTimeout(RESULT_TIMEOUT_MS) { recognize(readFd, languageTag) }
        } finally {
            writer.interrupt()
            runCatching { readFd.close() }
        }
    }

    private suspend fun recognize(readFd: ParcelFileDescriptor, languageTag: String): TranscriptResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
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
