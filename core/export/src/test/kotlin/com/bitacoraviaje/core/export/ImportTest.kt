package com.bitacoraviaje.core.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImportTest {
    /** Database-level content of an export, independent of ids and of things import cannot restore (content URIs). */
    private fun fingerprint(t: TripExport): String = buildString {
        appendLine("trip ${t.trip.title}|${t.trip.description}|${t.trip.status}|${t.trip.startedUtcMs}|${t.trip.endedUtcMs}")
        for (se in t.segments) {
            val s = se.segment
            appendLine("seg ${s.type}|${s.transport}|${s.title}|${s.startedUtcMs}|${s.endedUtcMs}|${s.tzId}|${s.samplingProfile}|${s.recordingMode}|${s.autoCreated}|${s.status}|${s.stats}")
            appendLine("pts ${se.points.size} " + se.points.joinToString(",") { "${it.timeUtcMs}:${it.lat}:${it.lon}:${it.altM}:${it.hAccuracyM}:${it.speedMps}:${it.isFilteredOut}" }.hashCode())
            appendLine("pauses " + se.pauses.joinToString(",") { "${it.kind}:${it.pausedUtcMs}:${it.resumedUtcMs}" })
            appendLine("stops " + se.stops.joinToString(",") { "${it.startUtcMs}:${it.endUtcMs}:${it.lat}:${it.lon}:${it.radiusM}:${it.userLabel}" })
            appendLine("media " + se.media.joinToString(",") { "${it.kind}:${it.displayName}:${it.takenUtcMs}:${it.takenTimeSource}:${it.linkedLat}:${it.linkedLon}:${it.linkMethod}:${it.linkConfidence}:${it.needsReview}:${it.userCaption}:${it.isFavorite}:${it.isExcluded}:${it.cameraMake}:${it.cameraModel}" })
            appendLine("voice " + se.voiceNotes.joinToString(",") { "${it.startedUtcMs}:${it.durationMs}:${it.lat}:${it.lon}:${it.displayText}:${it.transcriptStatus}:${it.transcriptLang}" })
            appendLine("text " + se.textNotes.joinToString(",") { "${it.kind}:${it.timeUtcMs}:${it.lat}:${it.lon}:${it.text}" })
            appendLine("weather " + se.weather.joinToString(",") { "${it.timeUtcMs}:${it.lat}:${it.lon}:${it.temperatureC}:${it.apparentC}:${it.precipitationMm}:${it.windKmh}:${it.cloudPct}:${it.weatherCode}:${it.source}" })
            appendLine("places " + t.placesOf(se.segment.id).joinToString(",") { (p, sp) -> "${p.id}:${p.name}:${p.kind}:${p.wikidataId}:${p.source}:${sp.firstSeenUtcMs}:${sp.dwellMs}:${sp.relevance}" })
        }
        appendLine("texts " + t.enrichments.joinToString(",") { "${it.id}:${it.placeId}:${it.lang}:${it.title}:${it.summary}:${it.sourceName}:${it.sourceUrl}:${it.license}:${it.userApproved}:${it.llmRewritten}" })
        appendLine("credits " + t.attributions.joinToString(",") { "${it.name}:${it.license}:${it.url}" })
    }

    @Test
    fun `trip json round trip gives the same database content`() {
        val original = Fixture.trip()
        val json = Fixture.render(TripJsonWriter(), original)
        val back = TripJsonReader.read(json)
        assertEquals(fingerprint(original.copy(segments = original.segments.map { se -> se.copy(points = se.points.filter { !it.isFilteredOut }) })), fingerprint(back))
        assertEquals(original.trip.id, back.trip.id)
        assertEquals(original.mediaFiles, back.mediaFiles)
        assertEquals(original.audioFiles, back.audioFiles)
    }

    @Test
    fun `importing as a copy regenerates every id consistently`() {
        val original = Fixture.trip()
        val back = TripJsonReader.read(Fixture.render(TripJsonWriter(), original), regenerateIds = true)
        assertNotEquals(original.trip.id, back.trip.id)
        assertTrue(back.segments.all { it.segment.tripId == back.trip.id })
        assertTrue(back.segments.all { se -> se.points.all { it.segmentId == se.segment.id } && se.media.all { it.segmentId == se.segment.id && it.tripId == back.trip.id } })
        assertEquals(original.mediaFiles.size, back.mediaFiles.size)
    }

    @Test
    fun `wrong schema version and garbage are rejected with a clear message`() {
        assertFailsWith<ImportException> { TripJsonReader.read("{\"schema_version\": 7}") }
        assertFailsWith<ImportException> { TripJsonReader.read("not json") }
    }

    @Test
    fun `ids that would escape a directory are rejected even when regenerating`() {
        val json = Fixture.render(TripJsonWriter(), Fixture.trip()).replace("\"id\": \"trip-1\"", "\"id\": \"../..\"")
        assertTrue(json.contains("../.."))
        assertFailsWith<ImportException> { TripJsonReader.read(json) }
        assertFailsWith<ImportException> { TripJsonReader.read(json, regenerateIds = true) }
        assertFailsWith<ImportException> { SafeId.require("a/b") }
        assertEquals("ok_1.x-Y", SafeId.require("ok_1.x-Y"))
    }

    @Test
    fun `gpx with a doctype is rejected`() {
        val gpx = "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY x \"y\">]><gpx version=\"1.1\" creator=\"x\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg><trkpt lat=\"-34.6\" lon=\"-58.4\"/><trkpt lat=\"-34.61\" lon=\"-58.4\"/></trkseg></trk></gpx>"
        assertFailsWith<ImportException> { GpxReader.read(ByteArrayInputStream(gpx.toByteArray()), "x.gpx", "UTC") }
    }

    @Test
    fun `gpx round trip keeps tracks, segments as pauses, points and waypoints`() {
        val original = Fixture.trip()
        val gpx = Fixture.render(GpxWriter(), original)
        val r = GpxReader.read(ByteArrayInputStream(gpx.toByteArray()), "viaje.gpx", Fixture.TZ, importUtcMs = Fixture.T0 + 10 * 86_400_000)
        assertEquals(2, r.trip.segments.size)
        val s1 = r.trip.segments[0]
        assertEquals(361 - 7, s1.points.size)
        assertEquals(original.segments[0].points.filter { !it.isFilteredOut }.map { it.timeUtcMs }, s1.points.map { it.timeUtcMs })
        assertEquals(2, s1.pauses.size, "two trkseg boundaries → two pauses")
        assertEquals(8, r.trip.segments.sumOf { it.textNotes.size }, "every waypoint becomes a marker")
        assertEquals(com.bitacoraviaje.core.model.Transport.CAR, r.trip.segments[1].segment.transport)
        assertTrue(r.trip.segments[0].segment.stats.distanceM!! > 3_000.0)
        assertTrue(r.warnings.none { it.contains("ficticias") })
    }

    @Test
    fun `gpx without times gets synthetic timestamps and a warning`() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Sendero</name><trkseg>
            <trkpt lat="-34.6" lon="-58.4"><ele>10</ele></trkpt><trkpt lat="-34.601" lon="-58.4"></trkpt><trkpt lat="-34.602" lon="-58.4"/></trkseg></trk></gpx>"""
        val r = GpxReader.read(ByteArrayInputStream(gpx.toByteArray()), "sendero.gpx", "UTC", importUtcMs = 1_000_000L)
        assertEquals(listOf(1_001_000L, 1_002_000L, 1_003_000L), r.trip.segments.single().points.map { it.timeUtcMs })
        assertEquals("Sendero", r.trip.trip.title)
        assertTrue(r.warnings.any { it.contains("ficticias") })
        assertFailsWith<ImportException> { GpxReader.read(ByteArrayInputStream("<gpx/>".toByteArray()), "x.gpx", "UTC") }
    }

    @Test
    fun `external gpx from the sample trips loads`() {
        val ins = javaClass.getResourceAsStream("/trekking/track.gpx") ?: error("sample gpx missing")
        val r = GpxReader.read(ins, "track.gpx", "America/Argentina/Buenos_Aires")
        assertTrue(r.trip.segments.single().points.size > 100)
        val out = ByteArrayOutputStream()
        GpxWriter().write(r.trip, out)
        assertTrue(out.size() > 1000)
    }
}
