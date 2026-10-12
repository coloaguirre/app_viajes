package com.bitacoraviaje.app

import com.bitacoraviaje.app.voice.AudioDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioDecoderTest {
    @Test
    fun `stereo 48 kHz becomes mono 16 kHz`() {
        val frames = 480
        val bb = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { bb.putShort(1000); bb.putShort(3000) }
        val out = AudioDecoder.toMono16k(bb.array(), 48_000, 2)
        assertEquals(160 * 2, out.size, "480 frames at 48 kHz -> 160 samples at 16 kHz, 2 bytes each")
        val first = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getShort(0)
        assertEquals(2000, first.toInt(), "channels averaged")
    }

    @Test
    fun `linear resampling keeps a constant signal and the edge values`() {
        val input = ShortArray(100) { 500 }
        assertEquals(50, AudioDecoder.resampleLinear(input, 32_000, 16_000).size)
        val ramp = ShortArray(10) { (it * 100).toShort() }
        val up = AudioDecoder.resampleLinear(ramp, 8_000, 16_000)
        assertEquals(20, up.size)
        assertEquals(0, up.first().toInt())
        assertEquals(850, up[17].toInt(), "17/2 = 8.5 -> between 800 and 900")
    }
}
