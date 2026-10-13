package com.bitacoraviaje.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * AAC/M4A, mono, 16 kHz, 32 kbps (DISENO §8.1): ~240 KB per minute, and what whisper
 * expects after decoding. One instance per recording.
 */
class VoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var focus: AudioFocusRequest? = null

    /** Set when the 5-minute cap stopped the recorder by itself: the file is complete, not short (review B1). */
    @Volatile private var autoStopped = false

    /** Called (on the recorder's event thread) when the cap is reached; the caller should save the note. */
    @Volatile var onMaxDurationReached: (() -> Unit)? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    val isRecording: Boolean get() = recorder != null

    /** 0..32767 peak since the last call; for the level meter. */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    fun hasFreeSpace(out: File, minBytes: Long = MIN_FREE_BYTES): Boolean {
        val dir = out.parentFile ?: context.filesDir
        dir.mkdirs()
        return StatFs(dir.absolutePath).availableBytes >= minBytes
    }

    fun start(out: File) {
        out.parentFile?.mkdirs()
        requestFocus()
        autoStopped = false
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(32_000)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(out.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        autoStopped = true
                        onMaxDurationReached?.invoke()
                    }
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            // prepare()/start() failed: release the half-built recorder or the mic stays taken (review I2).
            runCatching { r.release() }
            abandonFocus()
            throw e
        }
        recorder = r
    }

    /** Returns false when the note was too short to produce a file (DISENO §8.1): caller discards it. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        if (autoStopped) {
            // The cap already stopped it; stop() now would throw and the file is complete.
            r.release()
            recorder = null
            abandonFocus()
            return true
        }
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "voice note too short, discarded")
            false
        } finally {
            r.release()
            recorder = null
            abandonFocus()
        }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        abandonFocus()
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    companion object {
        const val TAG = "Bitacora"
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 5 * 60_000
        const val MIN_FREE_BYTES = 20L * 1024 * 1024

        fun fileFor(context: Context, tripId: String, noteId: String): File = File(context.filesDir, relativePath(tripId, noteId))

        fun relativePath(tripId: String, noteId: String): String = "audio/$tripId/$noteId.m4a"

        /** Duration of a recorded note as the container reports it (null if unreadable). */
        fun durationMs(file: File): Long? {
            val r = android.media.MediaMetadataRetriever()
            return try {
                r.setDataSource(file.path)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }
}
