package com.bitacoraviaje.app

import com.bitacoraviaje.app.settings.DEFAULT_NOMINATIM_URL
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

    @Test
    fun `invalid transcription model and engine fall back to the defaults with a report`() {
        val invalid = mutableListOf<Pair<String, String>>()
        val s = UserSettings.from(null, null, null, transcriptionModel = "huge", transcribeOnlyCharging = true, transcriptionEngine = "cloud") { k, v -> invalid += k to v }
        assertEquals("base", s.transcriptionModel)
        assertEquals("auto", s.transcriptionEngine)
        assertTrue(s.transcribeOnlyCharging)
        assertEquals(listOf("transcription_model" to "huge", "transcription_engine" to "cloud"), invalid)
        val ok = UserSettings.from(null, null, null, transcriptionModel = "small", transcriptionEngine = "whisper")
        assertEquals("small", ok.transcriptionModel)
        assertEquals("whisper", ok.transcriptionEngine)
    }

    @Test
    fun `service urls must be https unless they point at a loopback host and enrichment flags default on and off`() {
        val invalid = mutableListOf<Pair<String, String>>()
        val s = UserSettings.from(null, null, null, nominatimUrl = "http://nominatim.example.org", overpassUrl = "http://127.0.0.1:8080/api/interpreter") { k, v -> invalid += k to v }
        assertEquals(DEFAULT_NOMINATIM_URL, s.nominatimUrl)
        assertEquals("http://127.0.0.1:8080/api/interpreter", s.overpassUrl, "a local instance through loopback is allowed")
        assertEquals(listOf("nominatim_url" to "http://nominatim.example.org"), invalid)
        assertTrue(s.enrichPlaces)
        assertTrue(!s.roundEnrichmentCoords)
        val off = UserSettings.from(null, null, null, enrichPlaces = false, roundEnrichmentCoords = true)
        assertTrue(!off.enrichPlaces && off.roundEnrichmentCoords)
    }
}
