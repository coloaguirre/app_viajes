package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.PrivacyZone
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(stripped.contains("Copias JPEG reescritas sin GPS en su EXIF: 1") && !stripped.contains("conservan su propio EXIF"))
        // Nothing with a coordinate inside the zone survives in any text output.
        val inside = Fixture.trip().segments.flatMap { it.points }.filter { PrivacyFilter.inside(it.lat, it.lon, zones) }
        val csv = Fixture.render(PointsCsvWriter(), filtered)
        assertTrue(inside.none { p -> csv.contains("${p.lat},${p.lon}") })
    }
}
