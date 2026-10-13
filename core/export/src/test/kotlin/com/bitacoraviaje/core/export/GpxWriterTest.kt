package com.bitacoraviaje.core.export

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.xml.sax.InputSource

class GpxWriterTest {
    private fun validate(xml: String) {
        val xsd = javaClass.getResourceAsStream("/gpx.xsd") ?: error("gpx.xsd test resource missing")
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(StreamSource(xsd))
        schema.newValidator().validate(StreamSource(StringReader(xml)))
    }

    private fun dom(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    @Test
    fun `whole trip validates against the official GPX 1_1 schema`() {
        val xml = Fixture.render(GpxWriter())
        validate(xml)
        val d = dom(xml)
        assertEquals(2, d.getElementsByTagNameNS(GpxWriter.NS, "trk").length)
        // segment 1: two pauses → three trksegs; segment 2: one
        assertEquals(4, d.getElementsByTagNameNS(GpxWriter.NS, "trkseg").length)
        // waypoints: 2 located media + 2 voice + 3 text + 1 stop
        assertEquals(8, d.getElementsByTagNameNS(GpxWriter.NS, "wpt").length)
    }

    @Test
    fun `filtered points are excluded by default and included on request`() {
        val d = dom(Fixture.render(GpxWriter(listOf(0))))
        assertEquals(361 - 7, d.getElementsByTagNameNS(GpxWriter.NS, "trkpt").length)
        val all = dom(Fixture.render(GpxWriter(listOf(0)), Fixture.trip().copy(includeFilteredPoints = true)))
        assertEquals(361, all.getElementsByTagNameNS(GpxWriter.NS, "trkpt").length)
        assertEquals(7, all.getElementsByTagNameNS(GpxWriter.NS_BV, "filtered").length)
    }

    @Test
    fun `per segment file validates and special characters are escaped`() {
        val xml = Fixture.render(GpxWriter(listOf(0), "tracks/01.gpx"))
        validate(xml)
        assertTrue(xml.contains("&amp;") && xml.contains("&lt;amigos&gt;") && xml.contains("&quot;La Ñata&quot;"))
        assertFalse(xml.contains("<amigos>"))
        assertTrue(xml.contains("<time>2025-10-09T08:53:20Z</time>"), "UTC with Z")
        assertTrue(xml.contains("href=\"../media/fotos/2025-10-09/IMG_0001.jpg\""))
    }
}
