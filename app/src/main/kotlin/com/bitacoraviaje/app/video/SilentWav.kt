package com.bitacoraviaje.app.video

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A PCM WAV of silence (48 kHz stereo 16-bit, nominally 11.5 MB per minute but written sparse, so
 * it costs no real space). It is the primary audio item of the audio-only export: Media3 needs a
 * real item to set the composition's length (a sequence made only of gaps is not allowed) and the
 * primary input fixes the mixer's format, so it must be the full-quality one, not 16 kHz mono
 * (review I1 of the 7002 fix).
 */
object SilentWav {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    private const val BITS = 16

    fun write(file: File, durationMs: Long) {
        val frames = (durationMs * SAMPLE_RATE / 1000).coerceAtLeast(1)
        val dataBytes = frames * CHANNELS * BITS / 8
        require(dataBytes < Int.MAX_VALUE - 44) { "too long for a WAV: $durationMs ms" }
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(CHANNELS.toShort())
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * CHANNELS * BITS / 8).putShort((CHANNELS * BITS / 8).toShort()).putShort(BITS.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
            f.write(header.array())
            f.setLength(44 + dataBytes) // the data region reads as zeros: silence without writing it
        }
    }
}
