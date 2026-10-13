package com.bitacoraviaje.core.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xml.sax.InputSource

class OtherWritersTest {
    @Test
    fun `kml is well formed, one folder per segment, coordinates lon lat and simplified`() {
        val xml = Fixture.render(KmlWriter())
        val d = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        assertEquals(2, d.getElementsByTagName("Folder").length)
        val lines = d.getElementsByTagName("LineString")
        assertEquals(2, lines.length)
        val coordsEl = (lines.item(0) as org.w3c.dom.Element).getElementsByTagName("coordinates").item(0)
        val coords = coordsEl.textContent.trim().split(Regex("\\s+"))
        val first = coords.first().split(",")
        assertEquals(-58.4, first[0].toDouble(), 1e-6, "longitude first")
        assertEquals(-34.6, first[1].toDouble(), 1e-6)
        assertTrue(coords.size < 361 - 7, "simplified to 30 m: ${coords.size} vertices")
        assertTrue(xml.contains("<![CDATA["))
        assertTrue(xml.contains("<styleUrl>#track1</styleUrl>"))
    }

    @Test
    fun `kmz contains doc kml and the thumbnails`() {
        val out = ByteArrayOutputStream()
        KmzWriter(mapOf("m-1" to byteArrayOf(1, 2, 3))).write(Fixture.trip(), out)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> generateSequence { z.nextEntry }.forEach { names += it.name } }
        assertEquals(listOf("doc.kml", "files/m-1.jpg"), names)
    }

    @Test
    fun `geojson is a feature collection with lon lat positions and one feature per event`() {
        val doc = Json.parseToJsonElement(Fixture.render(GeoJsonWriter())).jsonObject
        assertEquals("FeatureCollection", doc["type"]!!.jsonPrimitive.content)
        val features = doc["features"]!!.jsonArray
        val kinds = features.groupingBy { it.jsonObject["properties"]!!.jsonObject["kind"]!!.jsonPrimitive.content }.eachCount()
        assertEquals(mapOf("track" to 2, "photo" to 1, "video" to 1, "voice" to 2, "marker" to 1, "highlight" to 1, "transport_change" to 1, "stop" to 1), kinds)
        val pos = features[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(-58.4, pos[0].jsonPrimitive.content.toDouble(), 1e-6)
        assertEquals(-34.6, pos[1].jsonPrimitive.content.toDouble(), 1e-6)
    }

    private fun parseCsv(text: String): List<List<String>> {
        assertTrue(text.startsWith("﻿"), "UTF-8 BOM")
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var i = 1
        val s = text
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                !quoted && c == '"' -> quoted = true
                !quoted && c == ',' -> { row.add(field.toString()); field = StringBuilder() }
                !quoted && c == '\r' -> Unit
                !quoted && c == '\n' -> { row.add(field.toString()); rows.add(row); row = ArrayList(); field = StringBuilder() }
                else -> field.append(c)
            }
            i++
        }
        return rows
    }

    @Test
    fun `csv tables re-parse with the expected rows and keep tricky text intact`() {
        val points = parseCsv(Fixture.render(PointsCsvWriter()))
        assertEquals(1 + (361 - 7) + 120, points.size)
        assertEquals(12, points[0].size)
        val media = parseCsv(Fixture.render(MediaCsvWriter()))
        assertEquals(4, media.size)
        assertEquals(Fixture.TRICKY, media[1][14])
        val notes = parseCsv(Fixture.render(NotesCsvWriter()))
        assertEquals(1 + 2 + 3, notes.size)
        assertEquals("voz", notes[1][2])
        val places = parseCsv(Fixture.render(PlacesCsvWriter()))
        assertEquals(2, places.size)
        assertEquals("5", places[1][10])
    }

    @Test
    fun `srt cues are timed from the segment start and skip notes without text`() {
        val srt = Fixture.render(SrtWriter())
        assertTrue(srt.startsWith("1\n00:15:00,000 --> 00:15:07,500\nLlegamos al mirador"), srt.lines().take(3).joinToString("|"))
        assertFalse(srt.contains("\n2\n"), "the failed note has no text and no cue")
    }

    @Test
    fun `markdown has day and segment headings and relative links`() {
        val md = Fixture.render(MarkdownWriter())
        assertTrue(md.startsWith("# Patagonia 2026 ${Fixture.TRICKY}"))
        assertTrue(md.contains("## 2025-10-09") && md.contains("## 2025-10-10"))
        assertTrue(md.contains("### Caminata inicial · a pie · 3.5 km · 50 min"))
        assertTrue(md.contains("![IMG_0001.jpg](media/fotos/2025-10-09/IMG_0001.jpg)"))
        assertTrue(md.contains("Cambio de medio: en bici"))
    }

    @Test
    fun `html embeds the trip json, escapes the title and references leaflet`() {
        val html = Fixture.render(HtmlWriter())
        val json = html.substringAfter("<script id=\"trip-data\" type=\"application/json\">").substringBefore("</script>")
        val doc = Json.parseToJsonElement(json.replace("<\\/", "</")).jsonObject
        assertEquals(2, doc["segments"]!!.jsonArray.size)
        assertTrue(html.contains("&lt;amigos&gt;"))
        assertTrue(html.contains("leaflet.min.js"))
        assertFalse(json.contains("</script"), "no premature script end inside the data")
    }
}
