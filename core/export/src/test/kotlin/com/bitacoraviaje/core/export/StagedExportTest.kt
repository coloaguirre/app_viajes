package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentStats
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Transport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StagedExportTest {
    private fun tempDir(): File = Files.createTempDirectory("staged-export").toFile()

    private fun zipEntries(bytes: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }

    @Test
    fun `an interrupted run resumes without rewriting or duplicating entries`() {
        val dir = tempDir()
        val trip = Fixture.trip()
        var opened = 0
        val flaky = FileSource { path ->
            opened++
            if (opened == 2) throw IOException("simulated interruption")
            if (path.endsWith("IMG_0003.jpg") || path.endsWith("v-2.m4a")) null else ByteArrayInputStream(ByteArray(5))
        }
        assertFailsWith<IOException> { StagedExport(dir).run(trip, flaky) }
        val manifestAfterCrash = File(dir, StagedExport.MANIFEST).readLines()
        val stagedDocs = manifestAfterCrash.count { it.startsWith("OK\t") }
        assertTrue(stagedDocs >= 14, "documents were staged before the crash: $stagedDocs")
        val mtimeBefore = File(dir, "trip.json").lastModified()
        Thread.sleep(20)

        val good = FileSource { path -> if (path.endsWith("IMG_0003.jpg") || path.endsWith("v-2.m4a")) null else ByteArrayInputStream(ByteArray(5)) }
        val report = StagedExport(dir).run(trip, good)
        assertEquals(mtimeBefore, File(dir, "trip.json").lastModified(), "already staged documents are not rewritten")
        val manifest = File(dir, StagedExport.MANIFEST).readLines()
        assertEquals(manifest.size, manifest.toSet().size, "no duplicated manifest lines")
        assertEquals(2, report.mediaWritten)
        assertEquals(1, report.audioWritten)
        assertEquals(listOf("media/fotos/2025-10-09/IMG_0003.jpg (id m-3)"), report.mediaMissing)
        assertEquals(listOf("audio/2025-10-09/nota_063320_v-2.m4a (nota v-2)"), report.audioMissing)

        val out = ByteArrayOutputStream()
        StagedExport(dir).zipTo(report, out)
        val names = zipEntries(out.toByteArray())
        assertEquals(names.size, names.toSet().size, "each entry once")
        assertEquals(report.entries.size, names.size)
        assertTrue(names.last().endsWith("/LEEME.txt"))
        StagedExport(dir).clear()
        assertTrue(!dir.exists())
    }

    @Test
    fun `a leftover staging made with other options is discarded instead of resumed`() {
        val dir = tempDir()
        val trip = Fixture.trip()
        val src = FileSource { ByteArrayInputStream(ByteArray(5)) }
        // First run: unfiltered, interrupted after the documents were staged.
        var opened = 0
        val flaky = FileSource { opened++; if (opened == 1) throw IOException("boom") else ByteArrayInputStream(ByteArray(5)) }
        assertFailsWith<IOException> { StagedExport(dir, fingerprint = "media=1;privacy=none").run(trip, flaky) }
        val unfilteredGpx = File(dir, "tracks/viaje-completo.gpx").readText()
        assertTrue(unfilteredGpx.contains("<trkpt"))
        // Second run: the same trip with privacy zones → nothing from the first run may survive (review B1).
        val (filtered, report) = PrivacyFilter.apply(trip, listOf(com.bitacoraviaje.core.model.PrivacyZone("z", "Casa", -34.6, -58.4, 600.0)))
        val staged = StagedExport(dir, fingerprint = "media=1;privacy=z@-34.6,-58.4,600")
        val out = staged.run(filtered, src, report)
        val gpxNow = File(dir, "tracks/viaje-completo.gpx").readText()
        assertTrue(gpxNow != unfilteredGpx && gpxNow.count { it == '<' } < unfilteredGpx.count { it == '<' }, "the GPX was regenerated from the filtered trip")
        assertEquals(out.pointsWritten, gpxNow.split("<trkpt").size - 1)
        val manifest = File(dir, StagedExport.MANIFEST).readLines()
        assertEquals("FP\tmedia=1;privacy=z@-34.6,-58.4,600", manifest.first())
        // Same fingerprint again: resumes (documents not rewritten).
        val mtime = File(dir, "trip.json").lastModified()
        Thread.sleep(20)
        StagedExport(dir, fingerprint = "media=1;privacy=z@-34.6,-58.4,600").run(filtered, src, report)
        assertEquals(mtime, File(dir, "trip.json").lastModified())
        staged.clear()
    }

    @Test
    fun `a synthetic 30 day trip exports with one gpx per day and stays fast`() {
        val base = Fixture.trip()
        val segments = (0 until 30).map { d ->
            val start = Fixture.T0 + d * 86_400_000L
            val s = Segment("day-$d", "trip-1", null, SegmentType.STAGE, Transport.WALK, "Día ${d + 1}", null, start, start + 6 * 3_600_000, Fixture.TZ, "WALK", RecordingMode.FULL, d > 0, SegmentStatus.FINISHED, SegmentStats(distanceM = 12_000.0))
            SegmentExport(s, Fixture.walk("day-$d", start, 720, stepS = 30))
        }
        val trip = base.copy(segments = segments, mediaFiles = emptyMap(), audioFiles = emptyMap())
        val dir = tempDir()
        val t0 = System.nanoTime()
        val report = StagedExport(dir).run(trip, { null })
        val out = ByteArrayOutputStream()
        StagedExport(dir).zipTo(report, out)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(30 * 720, report.pointsWritten)
        assertEquals(30, zipEntries(out.toByteArray()).count { Regex(".*/tracks/\\d{2}_\\d{4}-\\d{2}-\\d{2}_.*\\.gpx").matches(it) })
        assertTrue(elapsedMs < 30_000, "30-day export took $elapsedMs ms")
        StagedExport(dir).clear()
    }
}
