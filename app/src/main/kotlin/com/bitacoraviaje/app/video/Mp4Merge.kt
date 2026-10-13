package com.bitacoraviaje.app.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the video track of [video] and the audio track of [audio] into [out] without touching a
 * codec (PLAN 5.1, field fix 2026-10-13): on the Pixel 8 Pro a Transformer export that encodes video
 * and audio together deadlocks between the hardware video encoder and the muxer; rendering the
 * silent video and the mixed audio as two separate exports and merging the containers avoids it.
 */
object Mp4Merge {
    fun merge(video: File, audio: File, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        try {
            val vTrack = select(videoEx, "video/") ?: error("no video track in ${video.name}")
            val aTrack = select(audioEx, "audio/") ?: error("no audio track in ${audio.name}")
            val vOut = muxer.addTrack(videoEx.getTrackFormat(vTrack))
            val aOut = muxer.addTrack(audioEx.getTrackFormat(aTrack))
            val rotation = runCatching { videoEx.getTrackFormat(vTrack).getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
            if (rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()
            val bufferSize = maxOf(maxInputSize(videoEx.getTrackFormat(vTrack)), maxInputSize(audioEx.getTrackFormat(aTrack)), 2 * 1024 * 1024)
            interleave(videoEx, vOut, audioEx, aOut, muxer, bufferSize)
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            videoEx.release()
            audioEx.release()
        }
    }

    private fun select(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
                ex.selectTrack(i)
                return i
            }
        }
        return null
    }

    private fun maxInputSize(format: MediaFormat): Int = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)

    /** Writes the two tracks ordered by presentation time, so the MP4 interleaves video and audio like an encoder would. */
    private fun interleave(videoEx: MediaExtractor, vOut: Int, audioEx: MediaExtractor, aOut: Int, muxer: MediaMuxer, bufferSize: Int) {
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        var videoDone = false
        var audioDone = false
        while (!videoDone || !audioDone) {
            val takeVideo = !videoDone && (audioDone || videoEx.sampleTime <= audioEx.sampleTime)
            val ex = if (takeVideo) videoEx else audioEx
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) {
                if (takeVideo) videoDone = true else audioDone = true
                continue
            }
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ex.sampleTime
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(if (takeVideo) vOut else aOut, buffer, info)
            if (!ex.advance()) { if (takeVideo) videoDone = true else audioDone = true }
        }
    }
}
