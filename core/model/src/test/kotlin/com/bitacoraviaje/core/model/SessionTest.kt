package com.bitacoraviaje.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTest {
    @Test
    fun `segment types match the four session kinds of the spec`() {
        assertEquals(
            listOf("TRIP_START", "STAGE", "EXCURSION", "TREKKING"),
            SegmentType.entries.map { it.name },
        )
    }

    @Test
    fun `recording mode has exactly full and economy`() {
        assertEquals(setOf(RecordingMode.FULL, RecordingMode.ECONOMY), RecordingMode.entries.toSet())
    }
}
