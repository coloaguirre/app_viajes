package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestDetectorTest {
    private val t0 = 1_700_000_000_000L
    private fun p(tOffsetS: Int, northM: Double) = TrackPoint(segmentId = "s", timeUtcMs = t0 + tOffsetS * 1000L, lat = -34.6 + northM / 111_320, lon = -58.4)

    @Test
    fun `enters rest after five still minutes and exits on movement`() {
        val d = RestDetector()
        val events = (0..70).mapNotNull { i -> d.offer(p(i * 5, if (i % 2 == 0) 0.0 else 8.0)) }
        assertEquals(listOf(RestEvent.ENTER), events)
        assertTrue(d.isResting)
        assertNull(d.offer(p(400, 40.0)), "40 m is inside the exit radius: still resting")
        assertEquals(RestEvent.EXIT, d.offer(p(405, 80.0)))
        assertFalse(d.isResting)
    }

    @Test
    fun `walking never enters rest`() {
        val d = RestDetector()
        val events = straightWalk(600).mapNotNull { d.offer(it) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a gap with the same place counts as stillness`() {
        val d = RestDetector()
        assertNull(d.offer(p(0, 0.0)))
        assertEquals(RestEvent.ENTER, d.offer(p(20 * 60, 5.0)), "20 min later, 5 m away: resting")
    }

    @Test
    fun `silence after the last point enters rest on tick, movement exits`() {
        val d = RestDetector()
        assertNull(d.offer(p(0, 0.0)))
        assertNull(d.onTick(t0 + 4 * 60_000))
        assertEquals(RestEvent.ENTER, d.onTick(t0 + 5 * 60_000))
        assertNull(d.onTick(t0 + 6 * 60_000), "enter is reported once")
        assertNull(d.offer(p(7 * 60, 20.0)), "small wobble keeps resting")
        assertEquals(RestEvent.EXIT, d.offer(p(8 * 60, 90.0)))
    }

    @Test
    fun `economy style config with a shorter threshold`() {
        val d = RestDetector(RestConfig(enterAfterMs = 3 * 60_000))
        assertNull(d.offer(p(0, 0.0)))
        assertNull(d.offer(p(170, 3.0)))
        assertEquals(RestEvent.ENTER, d.offer(p(181, 3.0)))
    }
}
