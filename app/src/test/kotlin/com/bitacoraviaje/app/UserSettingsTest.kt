package com.bitacoraviaje.app

import com.bitacoraviaje.app.settings.DEFAULT_TRANSCRIPTION_LANGUAGE
import com.bitacoraviaje.app.settings.UserSettings
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserSettingsTest {
    @Test
    fun `never-set store yields defaults without reporting anything`() {
        val invalid = mutableListOf<Pair<String, String>>()
        val s = UserSettings.from(null, null, null) { k, v -> invalid += k to v }
        assertEquals(UserSettings(Units.METRIC, RecordingMode.FULL, DEFAULT_TRANSCRIPTION_LANGUAGE), s)
        assertTrue(invalid.isEmpty())
    }

    @Test
    fun `valid stored values are parsed`() {
        val s = UserSettings.from("IMPERIAL", "ECONOMY", "en-US", restAfterMin = 10)
        assertEquals(10, s.restAfterMin)
        assertEquals(Units.IMPERIAL, s.units)
        assertEquals(RecordingMode.ECONOMY, s.recordingMode)
        assertEquals("en-US", s.transcriptionLanguage)
    }

    @Test
    fun `unknown stored values fall back to defaults and are reported`() {
        val invalid = mutableListOf<Pair<String, String>>()
        val s = UserSettings.from("FURLONGS", "TURBO", "  ", restAfterMin = 999) { k, v -> invalid += k to v }
        assertEquals(Units.METRIC, s.units)
        assertEquals(RecordingMode.FULL, s.recordingMode)
        assertEquals(DEFAULT_TRANSCRIPTION_LANGUAGE, s.transcriptionLanguage)
        assertEquals(5, s.restAfterMin)
        assertEquals(listOf("units" to "FURLONGS", "recording_mode" to "TURBO", "rest_after_min" to "999"), invalid)
    }
}
