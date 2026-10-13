package com.bitacoraviaje.app

import com.bitacoraviaje.app.voice.LanguageDecision
import com.bitacoraviaje.app.voice.OnDeviceLanguages
import kotlin.test.Test
import kotlin.test.assertEquals

/** Review I3: the language choice is pure logic over the engine's three tag lists. */
class OnDeviceLanguagesTest {
    private val supported = listOf("de-DE", "en-US", "es-ES", "es-US", "es-419", "pt-BR")

    @Test
    fun `exact installed tag wins regardless of case`() {
        assertEquals(LanguageDecision.Use("es-AR"), OnDeviceLanguages.pick("es-ar", listOf("en-US", "es-AR", "es-US"), emptyList(), supported))
    }

    @Test
    fun `another installed variant of the same language is used, Latin American first`() {
        assertEquals(LanguageDecision.Use("es-US"), OnDeviceLanguages.pick("es-AR", listOf("en-US", "es-US", "es-ES"), emptyList(), supported))
        assertEquals(LanguageDecision.Use("es-419"), OnDeviceLanguages.pick("es-AR", listOf("es-US", "es-419", "es-ES"), emptyList(), supported))
        assertEquals(LanguageDecision.Use("es-ES"), OnDeviceLanguages.pick("es", listOf("es-ES"), emptyList(), supported))
    }

    @Test
    fun `pending download of the language means wait`() {
        assertEquals(LanguageDecision.Pending, OnDeviceLanguages.pick("es-AR", listOf("en-US"), listOf("es-US"), supported))
    }

    @Test
    fun `supported but not installed asks for a download of the closest variant`() {
        assertEquals(LanguageDecision.Download("es-419"), OnDeviceLanguages.pick("es-AR", listOf("en-US"), emptyList(), supported))
        assertEquals(LanguageDecision.Download("es-ES"), OnDeviceLanguages.pick("es-ES", listOf("en-US"), emptyList(), supported))
    }

    @Test
    fun `unsupported language and empty lists`() {
        assertEquals(LanguageDecision.Unsupported, OnDeviceLanguages.pick("gn-PY", listOf("es-US"), emptyList(), supported))
        assertEquals(LanguageDecision.Unsupported, OnDeviceLanguages.pick("es-AR", emptyList(), emptyList(), emptyList()))
    }
}
