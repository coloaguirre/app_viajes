package com.bitacoraviaje.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Backlog D3: one weather lookup per hour of the segment, where the traveller was at that hour. */
class WeatherSamplingTest {
    private val t0 = 1_760_000_000_000L // 2025-10-09 08:53:20 UTC

    @Test
    fun `empty input gives an empty plan`() {
        assertTrue(WeatherSampling.plan(emptyList(), t0, t0 + 3_600_000).isEmpty)
        assertTrue(WeatherSampling.plan(straightWalk(10), t0, t0).isEmpty)
    }

    @Test
    fun `one sample per clock hour, positioned at the hour's centre and rounded to a km`() {
        // 3 h walk at 1.1 m/s from 08:53:20: hours 08, 09, 10, 11 are touched.
        val pts = straightWalk(2160, t0 = t0) // 2160 × 5 s = 3 h
        val plan = WeatherSampling.plan(pts, pts.first().timeUtcMs, pts.last().timeUtcMs)
        assertEquals(4, plan.samples.size)
        assertEquals(t0 / 3_600_000 * 3_600_000, plan.samples.first().hourStartUtcMs)
        assertTrue(plan.samples.zipWithNext().all { (a, b) -> b.hourStartUtcMs - a.hourStartUtcMs == 3_600_000L })
        // Rounded to 2 decimals, and all on the walk's longitude.
        assertTrue(plan.samples.all { it.lon == -58.4 && it.lat == Math.round(it.lat * 100) / 100.0 })
        // The walk covers ~12 km north: latitudes move, so the samples are distinct locations.
        assertEquals(plan.samples.map { it.lat }.distinct().size, plan.locations.size)
        assertTrue(plan.samples.all { plan.locations[it.locationIndex] == (it.lat to it.lon) })
    }

    @Test
    fun `hours without a nearby fix are skipped and repeated positions share a location`() {
        // 10 min of fixes, then a 3 h gap, then 10 min more at the same place: the gap hours have no sample.
        val a = straightWalk(120, t0 = t0)
        val b = straightWalk(120, t0 = t0 + 3 * 3_600_000L + 600_000, lat0 = a.last().lat, stepM = 0.0)
        val plan = WeatherSampling.plan(a + b, a.first().timeUtcMs, b.last().timeUtcMs)
        val hours = plan.samples.map { (it.hourStartUtcMs / 3_600_000 % 24).toInt() }
        assertEquals(listOf(8, 9, 11, 12), hours, "hour 10 has no fix within 45 min and the 3 h gap is too long to interpolate")
        assertTrue(plan.locations.size <= 2, "the walk is 660 m: one or two rounded km cells")
    }

    @Test
    fun `the last hour's centre is clamped to the end, long segments are capped, and a pause never interpolates across`() {
        val pts = straightWalk(1644, t0 = t0) // 2 h 17 min: ends 11:10, before the centre of hour 11
        val plan = WeatherSampling.plan(pts, pts.first().timeUtcMs, pts.last().timeUtcMs)
        val last = plan.samples.last()
        assertEquals(pts.last().lat, plan.locations[last.locationIndex].first, 0.006, "hour 11 is placed where the walk ended (11:10), not where 11:30 would have been")
        assertEquals(3, WeatherSampling.plan(pts, pts.first().timeUtcMs, pts.last().timeUtcMs, maxHours = 3).samples.size)
        // A 95 min pause between two spots 20 km apart: without the pause hour 09 is interpolated on the straight line
        // between them; with it, the traveller is put at the fix where the pause began.
        val a = straightWalk(60, t0 = t0)
        val b = straightWalk(60, t0 = t0 + 100 * 60_000L, lat0 = -34.4)
        val pause = listOf(TimeInterval(a.last().timeUtcMs, b.first().timeUtcMs))
        val free = WeatherSampling.plan(a + b, a.first().timeUtcMs, b.last().timeUtcMs)
        val withPause = WeatherSampling.plan(a + b, a.first().timeUtcMs, b.last().timeUtcMs, pauses = pause)
        val hour9 = (t0 / 3_600_000 + 1) * 3_600_000
        val free9 = free.samples.single { it.hourStartUtcMs == hour9 }
        val paused9 = withPause.samples.single { it.hourStartUtcMs == hour9 }
        assertTrue(free9.lat > -34.58 && free9.lat < -34.42, "interpolated across the gap: ${free9.lat}")
        assertEquals(-34.6, paused9.lat, 0.011, "at the pause's fix, never in between")
    }
}
