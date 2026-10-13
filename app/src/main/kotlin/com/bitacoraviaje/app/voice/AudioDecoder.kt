package com.bitacoraviaje.app.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M4A/AAC → PCM 16-bit mono 16 kHz (what SpeechRecognizer's file source and whisper
 * need). Linear resampling is enough for speech (skill voice-journal).
 */
object AudioDecoder {
    const val TARGET_RATE = 16_000

    fun decodeToPcm16Mono16k(file: File): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                format = f
                break
            }
        }
        val fmt = format ?: throw TranscriptionException("no audio track in ${file.name}")
        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val out = ByteArrayOutputStream()
        var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes)
                        out.write(bytes)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return toMono16k(out.toByteArray(), sampleRate, channels)
    }

    internal fun toMono16k(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = src.remaining() / channels
        val mono = ShortArray(frames) { i ->
            var acc = 0
            for (c in 0 until channels) acc += src.get(i * channels + c)
            (acc / channels).toShort()
        }
        val resampled = if (sampleRate == TARGET_RATE) mono else resampleLinear(mono, sampleRate, TARGET_RATE)
        val bb = ByteBuffer.allocate(resampled.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        resampled.forEach { bb.putShort(it) }
        return bb.array()
    }

    internal fun resampleLinear(input: ShortArray, from: Int, to: Int): ShortArray {
        if (input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        return ShortArray(outLen) { i ->
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceIn(0, input.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(input.lastIndex)
            val f = pos - i0
            (input[i0] * (1 - f) + input[i1] * f).toInt().toShort()
        }
    }
}
