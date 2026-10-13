package com.bitacoraviaje.app.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineOrderTest {
    private class Fake(override val id: String) : Transcriber {
        override suspend fun isAvailable() = true
        override suspend fun transcribe(audio: File, languageTag: String) = TranscriptResult("", emptyList(), null)
    }

    @Test
    fun `engine preference drives the order and the stub always closes the list`() {
        val w = Fake("whisper-local")
        val s = Fake("android-speech")
        assertEquals(listOf("android-speech", "whisper-local", "stub"), TranscribeWorker.engineOrder("auto", w, s).map { it.id })
        assertEquals(listOf("whisper-local", "android-speech", "stub"), TranscribeWorker.engineOrder("whisper", w, s).map { it.id })
        assertEquals(listOf("android-speech", "stub"), TranscribeWorker.engineOrder("system", w, s).map { it.id })
        assertEquals(listOf("android-speech", "whisper-local", "stub"), TranscribeWorker.engineOrder("garbage", w, s).map { it.id })
    }
}
