package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN item 1.2 acceptance: the synthetic trekking sample, run through the filter and
 * the metrics, lands within ±2 % of the reference distance computed independently by
 * tools/sample-trips/make_trips.py, and its stop is detected.
 */
class SampleTripAcceptanceTest {
    @Test
    fun `trekking sample distance within 2 percent and one 20-minute stop`() {
        val raw = GpxFixture.read("/trekking/track.gpx")
        val reference = GpxFixture.referenceDouble("/trekking/reference.json", "distance_m")
        val (accepted, _) = acceptedBy(FilterConfig.WALK, raw)
        val stats = computeSegmentStats(accepted, transport = Transport.WALK)
        val dist = stats.distanceM!!
        val err = (dist - reference) / reference
        assertTrue(kotlin.math.abs(err) <= 0.02, "distance $dist vs reference $reference (${"%.2f".format(err * 100)} %)")
        assertEquals(300.0, stats.elevationGainM!!, 20.0)
        assertEquals(300.0, stats.elevationLossM!!, 20.0)
        val stops = detectStops(accepted)
        assertEquals(1, stops.size, "stops: $stops")
        val durationMin = (stops[0].endUtcMs - stops[0].startUtcMs) / 60_000.0
        assertTrue(durationMin in 18.0..22.0, "stop lasted $durationMin min")
    }

    @Test
    fun `car sample distance within 2 percent and one 12-minute stop`() {
        val raw = GpxFixture.read("/car/track.gpx")
        val reference = GpxFixture.referenceDouble("/car/reference.json", "distance_m")
        val (accepted, _) = acceptedBy(FilterConfig.VEHICLE, raw)
        val stats = computeSegmentStats(accepted, transport = Transport.CAR)
        val err = (stats.distanceM!! - reference) / reference
        assertTrue(kotlin.math.abs(err) <= 0.02, "distance ${stats.distanceM} vs $reference (${"%.2f".format(err * 100)} %)")
        val stops = detectStops(accepted, minDurationMs = 10 * 60_000)
        assertEquals(1, stops.size)
    }
}
