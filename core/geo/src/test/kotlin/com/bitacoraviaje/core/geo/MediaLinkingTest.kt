package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaLinkingTest {
    private val t0 = 1_700_000_000_000L
    private val track = straightWalk(121) // 10 min, 5 s cadence, 5.5 m steps north from -34.6,-58.4

    private fun seg(id: String, start: Long, end: Long?, parent: String? = null) = Segment(
        id = id, tripId = "t", parentSegmentId = parent, type = SegmentType.STAGE, startedUtcMs = start, endedUtcMs = end,
        tzId = "UTC", samplingProfile = "WALK", recordingMode = RecordingMode.FULL, status = SegmentStatus.FINISHED,
    )

    @Test
    fun `link config widens tolerances for vehicles and planes only`() {
        assertEquals(LinkConfig(), LinkConfig.forTransport(com.bitacoraviaje.core.model.Transport.WALK))
        assertEquals(LinkConfig(), LinkConfig.forTransport(null))
        assertEquals(2_000.0, LinkConfig.forTransport(com.bitacoraviaje.core.model.Transport.BUS).exifTrackMaxDistanceM)
        assertEquals(30 * 60_000L, LinkConfig.forTransport(com.bitacoraviaje.core.model.Transport.PLANE).maxNearestMs)
    }

    @Test
    fun `positionAt interpolates between neighbours and hits exact points`() {
        val p = positionAt(track, t0 + 2_500) as PositionAt.Interpolated
        assertEquals(track[0].lat + (track[1].lat - track[0].lat) / 2, p.lat, 1e-9)
        assertEquals(2_500L, p.gapMs)
        assertEquals(0.8, p.confidence)
        val exact = positionAt(track, track[10].timeUtcMs) as PositionAt.Interpolated
        assertEquals(track[10].lat, exact.lat)
        assertEquals(0L, exact.gapMs)
    }

    @Test
    fun `positionAt falls back to nearest within 10 min and to None beyond`() {
        val after = positionAt(track, track.last().timeUtcMs + 4 * 60_000) as PositionAt.Nearest
        assertEquals(track.last().lat, after.lat)
        assertEquals(0.7 * (1 - 4.0 / 10), after.confidence, 1e-9)
        assertEquals(PositionAt.None, positionAt(track, track.last().timeUtcMs + 11 * 60_000))
        assertEquals(PositionAt.None, positionAt(emptyList(), t0))
    }

    @Test
    fun `gap larger than 5 min between neighbours uses nearest, not interpolation`() {
        val sparse = listOf(track[0], track[0].copy(timeUtcMs = t0 + 6 * 60_000, lat = track[0].lat + 0.01))
        val p = positionAt(sparse, t0 + 60_000)
        assertTrue(p is PositionAt.Nearest)
        assertEquals(track[0].lat, p.lat)
    }

    @Test
    fun `EXIF GPS near the track links with confidence 1`() {
        val photoT = track[50].timeUtcMs + 20_000
        val l = linkMedia(photoT, track[50].lat + 3.0 / 111_320, track[50].lon, seg("s", t0, null), track)
        assertEquals(LinkMethod.EXIF, l.method)
        assertEquals(1.0, l.confidence)
        assertFalse(l.needsReview)
        assertEquals("s", l.segmentId)
    }

    @Test
    fun `EXIF GPS far from the track is flagged for review`() {
        val l = linkMedia(track[50].timeUtcMs, track[50].lat + 0.02, track[50].lon, seg("s", t0, null), track) // ~2.2 km away
        assertEquals(LinkMethod.EXIF, l.method)
        assertEquals(0.5, l.confidence)
        assertTrue(l.needsReview)
    }

    @Test
    fun `EXIF GPS without track nearby keeps 0_9`() {
        val l = linkMedia(track.last().timeUtcMs + 30 * 60_000, -34.7, -58.5, seg("s", t0, null), track)
        assertEquals(LinkMethod.EXIF, l.method)
        assertEquals(0.9, l.confidence)
        assertNull(l.timeGapMs)
    }

    @Test
    fun `without GPS the track position is interpolated, nearest, or none`() {
        assertEquals(LinkMethod.TRACK_INTERPOLATED, linkMedia(t0 + 2_500, null, null, seg("s", t0, null), track).method)
        val near = linkMedia(track.last().timeUtcMs + 60_000, null, null, seg("s", t0, null), track)
        assertEquals(LinkMethod.TRACK_NEAREST, near.method)
        assertEquals(0.7 * 0.9, near.confidence, 1e-9)
        val none = linkMedia(track.last().timeUtcMs + 3_600_000, null, null, seg("s", t0, null), track)
        assertEquals(LinkMethod.NONE, none.method)
        assertNull(none.lat)
    }

    @Test
    fun `segmentFor prefers the shortest containing segment and honours the margin`() {
        val day = seg("day", t0, t0 + 8 * 3_600_000)
        val hike = seg("hike", t0 + 3_600_000, t0 + 2 * 3_600_000, parent = "day")
        assertEquals("hike", segmentFor(t0 + 90 * 60_000, listOf(day, hike), t0)?.id)
        assertEquals("day", segmentFor(t0 + 5 * 3_600_000, listOf(day, hike), t0)?.id)
        assertEquals("day", segmentFor(t0 - 5 * 60_000, listOf(day, hike), t0)?.id, "10 min margin before start")
        assertNull(segmentFor(t0 - 20 * 60_000, listOf(day, hike), t0))
        val open = seg("open", t0, null)
        assertEquals("open", segmentFor(t0 + 3_600_000, listOf(open), nowUtcMs = t0 + 2 * 3_600_000)?.id)
    }

    @Test
    fun `camera clock offset from a manually placed photo`() {
        // Photo taken at track[40]'s place, but the camera clock says 1 h 02 min earlier.
        val photoClock = track[40].timeUtcMs - (62 * 60_000)
        val off = assertNotNull(cameraClockOffsetMs(track, track[40].lat, track[40].lon, photoClock))
        assertEquals(62 * 60_000L, off)
        assertNull(cameraClockOffsetMs(emptyList(), 0.0, 0.0, 0))
    }

    @Test
    fun `exif time with offset, local in the segment zone, and gps utc`() {
        // Real Pixel sample: DateTimeOriginal 2026:10:12 15:49:28, Offset -03:00 -> 18:49:28Z
        val withOff = assertNotNull(ExifTime.withOffset("2026:10:12 15:49:28", "-03:00", "997"))
        assertEquals(java.time.Instant.parse("2026-10-12T18:49:28.997Z").toEpochMilli(), withOff)
        // Same local string without offset, segment in Buenos Aires -> same instant; in Madrid -> 2 h different
        assertEquals(java.time.Instant.parse("2026-10-12T18:49:28Z").toEpochMilli(), ExifTime.local("2026:10:12 15:49:28", "America/Argentina/Buenos_Aires"))
        assertEquals(java.time.Instant.parse("2026-10-12T13:49:28Z").toEpochMilli(), ExifTime.local("2026:10:12 15:49:28", "Europe/Madrid"))
        assertEquals(java.time.Instant.parse("2026-10-12T18:49:25Z").toEpochMilli(), ExifTime.gpsUtc("2026:10:12", "18:49:25"))
        assertNull(ExifTime.withOffset("garbage", "-03:00"))
        assertNull(ExifTime.withOffset("2026:10:12 15:49:28", null))
    }
}
