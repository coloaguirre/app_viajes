package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.PrivacyZone
import com.bitacoraviaje.core.geo.haversineM
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PLAN 5.5 acceptance: points inside the radius do not reach the ZIP and every exclusion is counted. */
class PrivacyFilterTest {
    /** The fixture trip walks north from -34.60; a zone around the first 600 m (home, which also covers photo m-1 at 550 m) and one around the stop "Mirador". */
    private val home = PrivacyZone("z-home", "Casa", -34.6, -58.4, 600.0)
    private val mirador = PrivacyZone("z-mir", "Mirador", -34.59, -58.4, 60.0)

    @Test
    fun `points inside a zone are dropped, the track is cut and everything is counted`() {
        val original = Fixture.trip()
        val (filtered, report) = PrivacyFilter.apply(original, listOf(home, mirador))
        val insideBefore = original.segments.flatMap { it.points }.count { PrivacyFilter.inside(it.lat, it.lon, listOf(home, mirador)) }
        assertTrue(insideBefore > 0)
        assertEquals(insideBefore, report.pointsRemoved)
        assertTrue(filtered.segments.flatMap { it.points }.none { PrivacyFilter.inside(it.lat, it.lon, listOf(home, mirador)) })
        assertEquals(original.segments.flatMap { it.points }.size - insideBefore, filtered.segments.flatMap { it.points }.size)
        assertTrue(report.cuts >= 1, "a PRIVACY pause marks every entry into a zone: ${report.cuts}")
        assertTrue(filtered.segments.flatMap { it.pauses }.any { it.kind == PauseKind.PRIVACY })
        assertEquals(1, report.stopsRemoved, "the stop at the mirador is gone")
        assertEquals(1, report.placesRemoved, "the place at the mirador is gone")
        assertTrue(report.mediaBlanked >= 1 && filtered.allMedia.filter { PrivacyFilter.inside(it.linkedLat, it.linkedLon, listOf(home, mirador)) }.isEmpty())
        assertEquals(filtered.allMedia.size, original.allMedia.size, "media rows stay; only their location goes")
        assertTrue(filtered.enrichments.none { it.placeId == "wd-Q1" })
        assertEquals(report.zones, 2)
        val (same, none) = PrivacyFilter.apply(original, emptyList())
        assertTrue(none.isEmpty && same === original)
    }

    @Test
    fun `writers do not bridge the cut and the readme reports the exclusions`() {
        // "home" swallows the start of the walk (dropped, no cut needed); "mirador" sits mid-track → a PRIVACY cut.
        val zones = listOf(home, mirador)
        val (filtered, report) = PrivacyFilter.apply(Fixture.trip(), zones)
        assertTrue(report.cuts >= 1)
        val gpx = Fixture.render(GpxWriter(), filtered)
        assertTrue(gpx.split("<trkseg>").size - 1 >= 4, "the first segment has a privacy cut plus its two pauses, then seg-2")
        val kml = Fixture.render(KmlWriter(), filtered)
        assertTrue(kml.contains("<MultiGeometry>"), "KML splits the line at the cut")
        val geo = Fixture.render(GeoJsonWriter(), filtered)
        assertTrue(geo.contains("MultiLineString"))
        val html = Fixture.render(HtmlWriter(), filtered)
        assertTrue(html.contains("paused_utc_ms") && html.contains("pieces"), "the HTML diary splits by pauses too")
        val md = Fixture.render(MarkdownWriter(), filtered)
        assertTrue(md.contains("Tramo omitido por una zona de privacidad"))
        val leeme = LeemeWriter.text(filtered, ExportReport("f", emptyList(), 100, 90, 3, 3, emptyList(), 2, 2, emptyList(), 3, 1, privacy = report), true, true)
        assertTrue(leeme.contains("ZONAS DE PRIVACIDAD (2 aplicadas") && leeme.contains("Puntos del recorrido omitidos: ${report.pointsRemoved}"))
        assertTrue(leeme.contains("conservan su propio EXIF"), "without the strip option the readme warns about the JPEG copies")
        val stripped = LeemeWriter.text(filtered, ExportReport("f", emptyList(), 100, 90, 3, 3, emptyList(), 2, 2, emptyList(), 3, 1, privacy = report.copy(exifStripped = 1)), true, true)
        assertTrue(stripped.contains("Copias JPEG verificadas sin GPS en su EXIF: 1") && !stripped.contains("conservan su propio EXIF"))
        // Nothing with a coordinate inside the zone survives in any text output.
        val inside = Fixture.trip().segments.flatMap { it.points }.filter { PrivacyFilter.inside(it.lat, it.lon, zones) }
        val csv = Fixture.render(PointsCsvWriter(), filtered)
        assertTrue(inside.none { p -> csv.contains("${p.lat},${p.lon}") })
    }

    @Test
    fun `a cut closes where the track leaves the zone and a trip that ends inside closes at its last point`() {
        val original = Fixture.trip()
        val lastPoint = original.segments[0].points.last()
        val end = PrivacyZone("z-end", "Fin", lastPoint.lat, lastPoint.lon, 100.0)
        val (filtered, report) = PrivacyFilter.apply(original, listOf(mirador, end))
        // seg-1 enters the mirador and then the end zone; seg-2 (same start, shorter) also crosses the mirador: 3 entries, 3 cuts.
        assertEquals(3, report.cuts, "one cut per entry into a zone, across segments")
        val cuts = filtered.segments[0].pauses.filter { it.kind == PauseKind.PRIVACY }.sortedBy { it.pausedUtcMs }
        assertEquals(2, cuts.size)
        assertEquals(1, filtered.segments[1].pauses.count { it.kind == PauseKind.PRIVACY })
        assertTrue(cuts.all { it.resumedUtcMs != null }, "no privacy pause stays open (review I3)")
        val kept = filtered.segments[0].points
        val afterMirador = kept.first { it.timeUtcMs > cuts[0].pausedUtcMs }
        assertEquals(afterMirador.timeUtcMs, cuts[0].resumedUtcMs, "closed at the first kept point after the zone")
        assertEquals(lastPoint.timeUtcMs, cuts[1].resumedUtcMs, "a zone at the end closes at the last removed point")
        assertTrue(kept.none { it.timeUtcMs > cuts[1].pausedUtcMs }, "nothing survives after entering the final zone")
        // The diary summarises the legs between cuts instead of treating the rest of the stage as paused.
        val md = Fixture.render(MarkdownWriter(), filtered)
        assertTrue(md.contains("Fin del tramo omitido"), md.lines().filter { it.contains("omitido") }.joinToString())
    }

    @Test
    fun `a point exactly on the radius is inside and overlapping zones count each point once`() {
        val c = PrivacyZone("z", "c", -34.6, -58.4, 0.0)
        val d = haversineM(-34.601, -58.4, c.lat, c.lon)
        assertTrue(PrivacyFilter.inside(-34.601, -58.4, listOf(c.copy(radiusM = d))))
        assertTrue(!PrivacyFilter.inside(-34.601, -58.4, listOf(c.copy(radiusM = d - 0.01))))
        val (one, r1) = PrivacyFilter.apply(Fixture.trip(), listOf(home))
        val (two, r2) = PrivacyFilter.apply(Fixture.trip(), listOf(home, home.copy(id = "z2", radiusM = 300.0)))
        assertEquals(r1.pointsRemoved, r2.pointsRemoved)
        assertEquals(one.segments[0].points.size, two.segments[0].points.size)
    }

    @Test
    fun `credits bbox and stop references do not name the removed place and the filtered json still validates`() {
        val original = Fixture.trip().let { t ->
            // A stop that points at the place about to be removed (review M5).
            t.copy(segments = t.segments.mapIndexed { i, se -> if (i == 0) se.copy(stops = se.stops.map { it.copy(placeId = "wd-Q1") }) else se })
        }
        assertTrue(original.attributions.any { it.name.contains("Mirador") })
        val (filtered, _) = PrivacyFilter.apply(original, listOf(home, mirador))
        assertTrue(filtered.attributions.none { it.name.contains("Mirador") || it.url.contains("Mirador") }, "credits recomputed (review I1): ${filtered.attributions}")
        assertTrue(filtered.attributions.any { it.name.contains("OpenStreetMap") }, "OSM credit always stays")
        val bbox = assertNotNull(filtered.segments[0].segment.stats.bbox)
        val kept = filtered.segments[0].points
        assertTrue(bbox.minLat >= kept.minOf { it.lat } - 1e-9 && bbox.minLat > -34.5947, "bbox recomputed over kept points (review I2): $bbox")
        assertTrue(filtered.allStops.none { it.placeId == "wd-Q1" })
        val leeme = LeemeWriter.text(filtered, ExportReport("f", emptyList(), 1, 1, 0, 0, emptyList(), 0, 0, emptyList(), 0, 0, privacy = PrivacyReport(2, 1, 1, 0, 1, 0, 0)), true, true)
        assertTrue(!leeme.contains("wiki/Mirador"))
        val schema = JsonSchema.fromDefinition(javaClass.getResourceAsStream("/trip.schema.json")!!.readBytes().toString(Charsets.UTF_8))
        val errors = ArrayList<ValidationError>()
        assertTrue(schema.validate(Json.parseToJsonElement(Fixture.render(TripJsonWriter(), filtered)), errors::add), "filtered trip.json honours the contract (review I4): " + errors.take(5).joinToString { "${it.objectPath}: ${it.message}" })
    }

    @Test
    fun `the readme names copies that keep their own location metadata`() {
        val report = PrivacyReport(1, 5, 0, 0, 0, 2, 0, exifStripped = 1, mediaWithMetadata = listOf("media/videos/2025-10-09/VID_0002.mp4 (video)"))
        val leeme = LeemeWriter.text(Fixture.trip(), ExportReport("f", emptyList(), 1, 1, 0, 0, emptyList(), 0, 0, emptyList(), 0, 0, privacy = report), true, true)
        assertTrue(leeme.contains("verificadas sin GPS en su EXIF: 1") && leeme.contains("1 copias de adentro de una zona conservan la ubicación") && leeme.contains("VID_0002.mp4 (video)"))
    }
}
