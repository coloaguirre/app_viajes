package com.bitacoraviaje.app.voice

import com.bitacoraviaje.app.voice.models.ModelStore
import com.bitacoraviaje.app.voice.models.WhisperModel
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStoreTest {
    @Test
    fun `a download is committed only when its SHA-256 matches, otherwise it is discarded`() {
        val dir = Files.createTempDirectory("models").toFile()
        val part = File(dir, "x.part").apply { writeText("hola whisper") }
        val target = File(dir, "x.bin")
        val good = ModelStore.sha256(part)
        assertFalse(ModelStore.commitVerified(part, target, "00" + good.drop(2)), "wrong hash rejected")
        assertFalse(part.exists(), "bad download deleted so the next attempt starts clean")
        File(dir, "x.part").writeText("hola whisper")
        assertTrue(ModelStore.commitVerified(File(dir, "x.part"), target, good.uppercase()))
        assertTrue(target.isFile && !File(dir, "x.part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `installed means the file exists with the catalogued size`() {
        val dir = Files.createTempDirectory("models").toFile()
        val store = ModelStore(dir)
        assertFalse(store.isInstalled(WhisperModel.BASE))
        File(dir, WhisperModel.BASE.fileName).writeBytes(ByteArray(10))
        assertFalse(store.isInstalled(WhisperModel.BASE), "truncated file is not installed")
        assertEquals(WhisperModel.BASE, WhisperModel.byId("nope"))
        assertEquals(WhisperModel.SMALL, WhisperModel.byId("small"))
        store.delete(WhisperModel.BASE)
        assertFalse(File(dir, WhisperModel.BASE.fileName).exists())
        dir.deleteRecursively()
    }

    @Test
    fun `pcm16 little endian becomes floats in -1 to 1 and language tags map to whisper codes`() {
        val pcm = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0, 0x80.toByte())
        val f = WhisperLocalTranscriber.pcm16ToFloat(pcm)
        assertEquals(listOf(0f, 32767f / 32768f, -1f), f.toList())
        assertEquals("es", WhisperLocalTranscriber.whisperLanguage("es-AR"))
        assertEquals("auto", WhisperLocalTranscriber.whisperLanguage("AUTO"))
        assertEquals("en", WhisperLocalTranscriber.whisperLanguage("en-US"))
    }
}
