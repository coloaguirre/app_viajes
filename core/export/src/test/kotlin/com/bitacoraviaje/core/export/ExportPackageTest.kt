package com.bitacoraviaje.core.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportPackageTest {
    @Test
    fun `zip holds every document, copies the files it can open and lists the missing ones in LEEME`() {
        val trip = Fixture.trip()
        val out = ByteArrayOutputStream()
        val present = setOf("media/fotos/2025-10-09/IMG_0001.jpg", "media/videos/2025-10-09/VID_0002.mp4", "audio/2025-10-09/nota_061500_v-1.m4a")
        val progress = ArrayList<Pair<Int, Int>>()
        val report = ExportPackage(thumbnails = mapOf("m-1" to byteArrayOf(9))).write(trip, out, { p -> if (p in present) ByteArrayInputStream(ByteArray(10)) else null }) { d, t -> progress += d to t }
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> generateSequence { z.nextEntry }.forEach { e -> entries[e.name] = z.readBytes() } }
        val folder = report.folder
        assertEquals("Patagonia-2026-Cafe-La-Nata-amigos-noche_2025-10-12", folder)
        val names = entries.keys.map { it.removePrefix("$folder/") }
        for (expected in listOf("trip.json", "bitacora.md", "bitacora.html", "tracks/viaje-completo.gpx", "tracks/01_2025-10-09_caminata-inicial.gpx", "tracks/02_2025-10-10_etapa.gpx", "tracks/viaje.kml", "tracks/viaje.kmz", "tracks/viaje.geojson", "tablas/puntos.csv", "tablas/medios.csv", "tablas/notas.csv", "tablas/lugares.csv", "subtitulos/notas.srt", "LEEME.txt")) {
            assertTrue(expected in names, "missing $expected in $names")
        }
        assertEquals("LEEME.txt", names.last(), "LEEME is written last, with the final counts")
        assertTrue("media/fotos/2025-10-09/IMG_0001.jpg" in names)
        assertFalse("media/fotos/2025-10-09/IMG_0003.jpg" in names)
        // Reconciliation: database counts vs. what went into the zip, missing files named.
        assertEquals(3, report.mediaInDb)
        assertEquals(2, report.mediaWritten)
        assertEquals(listOf("media/fotos/2025-10-09/IMG_0003.jpg (id m-3)"), report.mediaMissing)
        assertEquals(2, report.voiceNotesInDb)
        assertEquals(1, report.audioWritten)
        assertEquals(361 - 7 + 120, report.pointsWritten)
        assertFalse(report.complete)
        val leeme = entries["$folder/LEEME.txt"]!!.toString(Charsets.UTF_8)
        assertTrue(leeme.contains("Fotos y videos: 2 copiados de 3 registrados"))
        assertTrue(leeme.contains("FALTANTES") && leeme.contains("IMG_0003.jpg (id m-3)") && leeme.contains("nota_063320_v-2.m4a (nota v-2)"))
        assertEquals(progress.last().first, progress.last().second, "progress reaches total")
        assertTrue(entries["$folder/trip.json"]!!.isNotEmpty() && entries["$folder/tracks/viaje.kmz"]!!.isNotEmpty())
    }

    @Test
    fun `media and audio can be left out by choice and LEEME says so`() {
        val trip = Fixture.trip()
        val out = ByteArrayOutputStream()
        val report = ExportPackage(includeMedia = false, includeAudio = false).write(trip, out, { null })
        assertTrue(report.complete)
        assertEquals(0, report.mediaWritten)
        assertTrue(report.entries.none { it.startsWith("media/") || it.startsWith("audio/") })
    }
}
