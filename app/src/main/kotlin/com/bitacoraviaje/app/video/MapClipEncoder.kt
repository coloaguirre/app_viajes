package com.bitacoraviaje.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * Encodes frames drawn with Canvas into a short H.264 MP4 (the animated map of DISENO §13.4
 * on the device, skill trip-video §4 "prerenderizar y codificar con MediaCodec + MediaMuxer").
 * The clip then enters the Transformer composition as an ordinary video item.
 */
interface ClipEncoder {
    fun encode(out: File, width: Int, height: Int, fps: Int, frames: Int, draw: (index: Int, canvas: Canvas) -> Unit)
}

class MapClipEncoder : ClipEncoder {
    override fun encode(out: File, width: Int, height: Int, fps: Int, frames: Int, draw: (Int, Canvas) -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.12).toInt().coerceIn(2_000_000, 16_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = codec.createInputSurface()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val frameUs = 1_000_000L / fps
        // Frames are drawn to a bitmap first (software Canvas) and blitted to the surface: deterministic and simple.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bmpCanvas = Canvas(bitmap)
        fun drain(end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 20_000 else 0)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxing) muxer.writeSampleData(track, buf, info)
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(i, false)
                        if (eos) return
                    }
                }
                if (end && i == MediaCodec.INFO_TRY_AGAIN_LATER && muxing) return
            }
        }
        try {
            codec.start()
            // A Surface fed through lockCanvas stamps each frame with the wall clock at post time, so the
            // clip lasts exactly as long as the drawing took. Pacing the posts at 1/fps keeps the clip's
            // duration equal to the planned one (a 5 s map scene really costs 5 s of encoding).
            val t0 = android.os.SystemClock.elapsedRealtimeNanos()
            for (f in 0 until frames) {
                draw(f, bmpCanvas)
                val due = t0 + f * frameUs * 1000
                val wait = (due - android.os.SystemClock.elapsedRealtimeNanos()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
                val c = surface.lockCanvas(Rect(0, 0, width, height))
                try { c.drawBitmap(bitmap, 0f, 0f, null) } finally { surface.unlockCanvasAndPost(c) }
                drain(end = false)
            }
            codec.signalEndOfInputStream()
            drain(end = true)
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (muxing) muxer.stop() }; muxer.release()
            surface.release()
            bitmap.recycle()
        }
    }
}
