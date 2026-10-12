package com.bitacoraviaje.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamplingProfileTest {
    @Test
    fun `session profile follows type, transport and mode`() {
        assertEquals(SamplingProfile.TREKKING_HIGH, SamplingProfile.forSession(SegmentType.TREKKING, Transport.WALK, RecordingMode.FULL))
        assertEquals(SamplingProfile.WALK, SamplingProfile.forSession(SegmentType.STAGE, null, RecordingMode.FULL))
        assertEquals(SamplingProfile.VEHICLE, SamplingProfile.forSession(SegmentType.STAGE, Transport.BUS, RecordingMode.FULL))
        assertEquals(SamplingProfile.ECONOMY, SamplingProfile.forSession(SegmentType.TREKKING, Transport.WALK, RecordingMode.ECONOMY))
        assertEquals(SamplingProfile.PLANE, SamplingProfile.forSession(SegmentType.STAGE, Transport.PLANE, RecordingMode.ECONOMY))
    }

    @Test
    fun `economy samples at least five times less often than walk`() {
        assertTrue(SamplingProfile.ECONOMY.intervalMs >= 5 * SamplingProfile.WALK.intervalMs)
        assertTrue(SamplingProfile.ECONOMY.minDistanceM >= 5 * SamplingProfile.WALK.minDistanceM)
    }
}
