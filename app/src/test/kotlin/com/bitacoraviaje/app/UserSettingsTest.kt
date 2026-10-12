package com.bitacoraviaje.app

import com.bitacoraviaje.app.settings.Units
import com.bitacoraviaje.app.settings.UserSettings
import com.bitacoraviaje.core.model.RecordingMode
import kotlin.test.Test
import kotlin.test.assertEquals

class UserSettingsTest {
    @Test
    fun `defaults are metric, full recording and rioplatense spanish`() {
        val s = UserSettings()
        assertEquals(Units.METRIC, s.units)
        assertEquals(RecordingMode.FULL, s.recordingMode)
        assertEquals("es-AR", s.transcriptionLanguage)
    }
}
