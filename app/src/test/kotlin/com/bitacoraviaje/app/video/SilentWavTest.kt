package com.bitacoraviaje.app.video

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SilentWavTest {
    @Test
    fun `writes a valid pcm header and a zero body of the requested length`() {
        val f = File(Files.createTempDirectory("wav").toFile(), "s.wav")
        SilentWav.write(f, 2_500)
        val bytes = f.readBytes()
        assertEquals(44 + 2_500 * 16_000 / 1000 * 2, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4)); assertEquals("fmt ", String(bytes, 12, 4)); assertEquals("data", String(bytes, 36, 4))
        assertEquals(1, b.getShort(20).toInt(), "PCM")
        assertEquals(1, b.getShort(22).toInt(), "mono")
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28), "byte rate")
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(bytes.size - 44, b.getInt(40), "data size")
        assertEquals(bytes.size - 8, b.getInt(4), "riff size")
        assert(bytes.drop(44).all { it == 0.toByte() })
    }
}
