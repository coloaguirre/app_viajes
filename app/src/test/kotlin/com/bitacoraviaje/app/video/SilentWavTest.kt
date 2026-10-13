package com.bitacoraviaje.app.video

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SilentWavTest {
    @Test
    fun `writes a valid pcm header and a zero body of the requested length`() {
        val f = File(Files.createTempDirectory("wav").toFile(), "s.wav")
        SilentWav.write(f, 2_500)
        val bytes = f.readBytes()
        assertEquals(44 + 2_500 * 48_000 / 1000 * 4, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4)); assertEquals("fmt ", String(bytes, 12, 4)); assertEquals("data", String(bytes, 36, 4))
        assertEquals(1, b.getShort(20).toInt(), "PCM")
        assertEquals(2, b.getShort(22).toInt(), "stereo: the primary input fixes the mixer's format")
        assertEquals(48_000, b.getInt(24))
        assertEquals(192_000, b.getInt(28), "byte rate")
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(bytes.size - 44, b.getInt(40), "data size")
        assertEquals(bytes.size - 8, b.getInt(4), "riff size")
        assertTrue(bytes.drop(44).all { it == 0.toByte() })
        // The JVM's own WAV reader accepts it: not just our offsets re-read by hand.
        javax.sound.sampled.AudioSystem.getAudioInputStream(f).use { ais ->
            assertEquals(48_000f, ais.format.sampleRate); assertEquals(2, ais.format.channels); assertEquals(16, ais.format.sampleSizeInBits)
            assertEquals(2_500L * 48, ais.frameLength)
        }
        val zero = File(f.parentFile, "z.wav")
        SilentWav.write(zero, 0)
        assertEquals(44 + 4, zero.length(), "never an empty data chunk")
    }
}
