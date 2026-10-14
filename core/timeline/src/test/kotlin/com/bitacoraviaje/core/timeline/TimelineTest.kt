package com.bitacoraviaje.core.timeline

import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.VoiceNote
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineTest {
    private val tz = "America/Argentina/Buenos_Aires"
    private val t0 = Instant.parse("2026-03-14T20:00:00Z").toEpochMilli() // 17:00 local
    private fun walk(n: Int, t: Long = t0, lat0: Double = -34.6) = (0 until n).map { i -> TrackPoint(segmentId = "s", timeUtcMs = t + i * 5_000L, lat = lat0 + i * 5.5 / 111_320, lon = -58.4) }
    private fun seg(start: Long = t0, end: Long? = null) = Segment("s", "t", null, SegmentType.STAGE, null, null, null, start, end, tz, "WALK", RecordingMode.FULL, false, SegmentStatus.FINISHED)
    private fun media(id: String, t: Long, lat: Double?, lon: Double?) = MediaItem(id, "t", "s", MediaKind.PHOTO, MediaSource.MEDIASTORE, takenUtcMs = t, takenTimeSource = TakenTimeSource.EXIF_WITH_OFFSET, linkedLat = lat, linkedLon = lon, linkMethod = LinkMethod.EXIF, linkConfidence = 1.0)

    @Test
    fun `events are time ordered with start and end and borrowed positions`() {
        val pts = walk(241) // 20 min
        val end = pts.last().timeUtcMs
        val tl = buildTimeline(TimelineInput(seg(end = end), pts, voiceNotes = listOf(VoiceNote("v", "s", t0 + 60_000, 3_000, null, null, null, "a.m4a"))))
        assertTrue(tl.first() is TimelineEvent.SegmentStart)
        assertTrue(tl.last() is TimelineEvent.SegmentEnd)
        assertEquals(tl.map { it.timeUtcMs }, tl.map { it.timeUtcMs }.sorted())
        val v = tl.filterIsInstance<TimelineEvent.Voice>().single()
        assertNotNull(v.lat, "voice note without position borrows it from the track")
    }

    @Test
    fun `photos within 2 min and 100 m collapse into one moment, far ones do not`() {
        val pts = walk(241)
        val a = media("a", t0 + 100_000, -34.6, -58.4)
        val b = media("b", t0 + 130_000, -34.6 + 20.0 / 111_320, -58.4)
        val c = media("c", t0 + 600_000, -34.6 + 500.0 / 111_320, -58.4)
        val moments = buildTimeline(TimelineInput(seg(end = pts.last().timeUtcMs), pts, media = listOf(c, a, b))).filterIsInstance<TimelineEvent.Moment>()
        assertEquals(2, moments.size)
        assertEquals(listOf("a", "b"), moments[0].media.map { it.id })
        assertTrue(moments[1].isSingle)
    }

    @Test
    fun `pauses, stops and highlights appear with their kinds and durations`() {
        val pts = walk(361) // 30 min
        val pause = SegmentPause("p", "s", PauseKind.AUTO_REST, t0 + 300_000, t0 + 600_000)
        val stop = Stop("st", "s", t0 + 300_000, t0 + 600_000, -34.6, -58.4, 30.0)
        val hl = TextNote("h", "s", t0 + 900_000, null, null, TextNoteKind.HIGHLIGHT)
        val tc = TextNote("c", "s", t0 + 1_000_000, null, null, TextNoteKind.TRANSPORT_CHANGE, "CAR")
        val tl = buildTimeline(TimelineInput(seg(end = pts.last().timeUtcMs), pts, pauses = listOf(pause), stops = listOf(stop), textNotes = listOf(hl, tc)))
        assertEquals(com.bitacoraviaje.core.model.Transport.CAR, tl.filterIsInstance<TimelineEvent.TransportChange>().single().transport)
        assertEquals(PauseKind.AUTO_REST, tl.filterIsInstance<TimelineEvent.Pause>().single().kind)
        assertEquals(300_000L, tl.filterIsInstance<TimelineEvent.Resume>().single().pausedMs)
        assertEquals(300_000L, tl.filterIsInstance<TimelineEvent.StopLeft>().single().dwellMs)
        assertEquals(1, tl.filterIsInstance<TimelineEvent.Highlight>().size)
    }

    @Test
    fun `a summary row is inserted for long legs only`() {
        val pts = walk(721) // 60 min, ~4 km
        val note = TextNote("n", "s", t0 + 30 * 60_000, null, null, TextNoteKind.MARKER, "mitad")
        val tl = buildTimeline(TimelineInput(seg(end = pts.last().timeUtcMs), pts, textNotes = listOf(note)))
        val summaries = tl.filterIsInstance<TimelineEvent.Summary>()
        assertEquals(2, summaries.size, "start→marker and marker→end")
        assertTrue(summaries[0].distanceM in 1_900.0..2_100.0, "got ${summaries[0].distanceM}")
        // short leg: two markers 1 min apart produce no summary between them
        val n2 = TextNote("n2", "s", t0 + 31 * 60_000, null, null, TextNoteKind.MARKER, "cerca")
        val tl2 = buildTimeline(TimelineInput(seg(end = pts.last().timeUtcMs), pts, textNotes = listOf(note, n2)))
        assertEquals(2, tl2.filterIsInstance<TimelineEvent.Summary>().size)
    }

    @Test
    fun `day change separators at local midnight for a segment that crosses it`() {
        val start = Instant.parse("2026-03-15T02:30:00Z").toEpochMilli() // 23:30 local
        val pts = walk(721, t = start) // 1 h
        val tl = buildTimeline(TimelineInput(seg(start = start, end = pts.last().timeUtcMs), pts))
        val dc = tl.filterIsInstance<TimelineEvent.DayChange>().single()
        assertEquals(Instant.parse("2026-03-15T03:00:00Z").toEpochMilli(), dc.timeUtcMs)
        assertEquals(java.time.LocalDate.of(2026, 3, 15), dc.localDate)
    }

    @Test
    fun `no points at all still yields start, end and unplaced media without positions`() {
        val m = MediaItem("m", "t", "s", MediaKind.PHOTO, MediaSource.MEDIASTORE, takenUtcMs = t0 + 60_000, takenTimeSource = TakenTimeSource.MEDIASTORE)
        val tl = buildTimeline(TimelineInput(seg(end = t0 + 120_000), emptyList(), media = listOf(m)))
        assertEquals(listOf("SegmentStart", "Moment", "SegmentEnd"), tl.map { it::class.simpleName })
        assertTrue(tl.all { it.lat == null && it.lon == null })
    }

    @Test
    fun `open segment uses now and has no end event`() {
        val pts = walk(61)
        val tl = buildTimeline(TimelineInput(seg(), pts, nowUtcMs = pts.last().timeUtcMs + 10_000))
        assertTrue(tl.none { it is TimelineEvent.SegmentEnd })
    }

    @Test
    fun `places visited appear at first sight clamped to the segment and after a stop arrival`() {
        val pts = walk(200)
        val stop = Stop("st", "s", t0 + 100_000, t0 + 400_000, pts[20].lat, -58.4, 30.0)
        val place = com.bitacoraviaje.core.model.Place("wd-Q1", "Pueblo", pts[20].lat, -58.4, kind = "town", source = com.bitacoraviaje.core.model.PlaceSource.WIKIPEDIA)
        val visit = com.bitacoraviaje.core.model.SegmentPlace("s", "wd-Q1", t0 + 100_000, 300_000, 2.0)
        val early = place.copy(id = "wd-Q2", name = "Antes") to com.bitacoraviaje.core.model.SegmentPlace("s", "wd-Q2", t0 - 999_999, null, 1.0)
        val ev = buildTimeline(TimelineInput(seg(end = t0 + 1_000_000), pts, stops = listOf(stop), places = listOf(place to visit, early)))
        val visited = ev.filterIsInstance<TimelineEvent.PlaceVisited>()
        assertEquals(listOf("Antes", "Pueblo"), visited.map { it.place.name })
        assertEquals(t0, visited[0].timeUtcMs, "a visit before the segment start is clamped to it")
        assertTrue(ev.indexOfFirst { it is TimelineEvent.StopArrived } < ev.indexOf(visited[1]), "the place row follows the arrival at the stop")
        assertEquals(pts[20].lat, visited[1].lat)
    }

    @Test
    fun `weather blocks appear once per block, tag photos and notes with the hour's sample and do not split summaries`() {
        val pts = walk(2881) // 4 h from 17:00 local
        val end = pts.last().timeUtcMs
        val hour = t0 / 3_600_000 * 3_600_000
        val samples = (0 until 4).map { i -> com.bitacoraviaje.core.model.WeatherSample("w$i", "s", hour + i * 3_600_000L + 1_800_000, -34.6, -58.4, 20.0 + i, 19.0, 0.0, 5.0, 10, if (i < 3) 1 else 61) }
        val tl = buildTimeline(TimelineInput(seg(end = end), pts, media = listOf(media("m", t0 + 70 * 60_000, null, null)), weather = samples))
        val blocks = tl.filterIsInstance<TimelineEvent.Weather>()
        assertEquals(listOf(WeatherDigest.Block.AFTERNOON, WeatherDigest.Block.NIGHT), blocks.map { it.block.block }, "17–20 local is afternoon, 20–21 is night")
        assertEquals("Mayormente despejado, 20–22 °C", blocks[0].block.text)
        assertEquals("Lluvia ligera, 23 °C", blocks[1].block.text)
        assertTrue(blocks.all { it.timeUtcMs >= t0 }, "clamped into the segment")
        val m = tl.filterIsInstance<TimelineEvent.Moment>().single()
        assertEquals("w1", m.weather?.id, "the photo at 18:10 gets the 18:30 sample")
        // Without weather events as anchors the 4 h walk still yields its summaries between real events.
        val withWx = tl.count { it is TimelineEvent.Summary }
        val without = buildTimeline(TimelineInput(seg(end = end), pts, media = listOf(media("m", t0 + 70 * 60_000, null, null)))).count { it is TimelineEvent.Summary }
        assertEquals(without, withWx)
    }
}
