package com.bitacoraviaje.core.export

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Resumable variant of [ExportPackage] (PLAN 3.2): every entry is first materialised as a
 * file under [stagingDir] and recorded in `manifest.txt`; a second run after an
 * interruption skips what is already there, so nothing is written twice. [zipTo] then
 * streams the staged files into the final ZIP in a fixed order. `LEEME.txt` is always
 * regenerated last because it carries the final counts.
 */
class StagedExport(
    private val stagingDir: File,
    private val thumbnails: Map<String, ByteArray> = emptyMap(),
    private val includeMedia: Boolean = true,
    private val includeAudio: Boolean = true,
) {
    private val manifest get() = File(stagingDir, MANIFEST)

    /** Entries already staged (path → ok) and files known to be missing, from a previous run. */
    private fun readManifest(): Pair<MutableSet<String>, MutableSet<String>> {
        val ok = LinkedHashSet<String>()
        val missing = LinkedHashSet<String>()
        if (manifest.exists()) {
            manifest.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "OK" -> parts.getOrNull(1)?.let { if (File(stagingDir, it).isFile) ok += it }
                    "MISSING" -> parts.getOrNull(1)?.let { missing += it }
                }
            }
        }
        return ok to missing
    }

    private fun note(line: String) = manifest.appendText(line + "\n", Charsets.UTF_8)

    fun run(trip: TripExport, files: FileSource, privacy: PrivacyReport? = null, onProgress: ((done: Int, total: Int) -> Unit)? = null): ExportReport {
        stagingDir.mkdirs()
        val folder = Names.packageFolder(trip.trip.title, trip.exportedUtcMs, trip.tzId)
        val (ok, missing) = readManifest()
        val writers = ExportPackage.writers(trip, thumbnails)
        val mediaPaths = if (includeMedia) trip.mediaFiles else emptyMap()
        val audioPaths = if (includeAudio) trip.audioFiles else emptyMap()
        val total = writers.size + mediaPaths.size + audioPaths.size + 1
        var done = 0
        fun stage(path: String, body: (OutputStream) -> Unit) {
            if (path in ok) return
            val target = File(stagingDir, path)
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            FileOutputStream(tmp).use(body)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "cannot stage $path" }
            }
            ok += path
            note("OK\t$path")
        }
        for (wr in writers) {
            stage(wr.path) { wr.write(trip, it) }
            onProgress?.invoke(++done, total)
        }
        val mediaMissing = ArrayList<String>()
        val audioMissing = ArrayList<String>()
        fun copy(kind: String, id: String, path: String, into: MutableList<String>) {
            val key = "$path (${if (kind == "media") "id" else "nota"} $id)"
            if (path in ok) return
            if (path in missing) {
                into += key
                return
            }
            val ins = files.open(path)
            if (ins == null) {
                into += key
                missing += path
                note("MISSING\t$path")
            } else {
                stage(path) { o -> ins.use { it.copyTo(o) } }
            }
        }
        for ((id, path) in mediaPaths) {
            copy("media", id, path, mediaMissing)
            onProgress?.invoke(++done, total)
        }
        for ((id, path) in audioPaths) {
            copy("audio", id, path, audioMissing)
            onProgress?.invoke(++done, total)
        }
        val order = writers.map { it.path } + mediaPaths.values.filter { it in ok } + audioPaths.values.filter { it in ok }
        val report = ExportReport(
            folder = folder,
            entries = order + "LEEME.txt",
            pointsInDb = trip.segments.sumOf { se -> se.points.count { trip.includeFilteredPoints || !it.isFilteredOut } },
            pointsWritten = countTrkpt(File(stagingDir, "tracks/viaje-completo.gpx")),
            mediaInDb = trip.allMedia.size,
            mediaWritten = mediaPaths.values.count { it in ok },
            mediaMissing = mediaMissing,
            voiceNotesInDb = trip.allVoiceNotes.size,
            audioWritten = audioPaths.values.count { it in ok },
            audioMissing = audioMissing,
            textNotesInDb = trip.allTextNotes.size,
            stopsInDb = trip.allStops.size,
            privacy = privacy,
        )
        ok.remove("LEEME.txt")
        File(stagingDir, "LEEME.txt").writeText(LeemeWriter.text(trip, report, includeMedia, includeAudio), Charsets.UTF_8)
        onProgress?.invoke(++done, total)
        return report
    }

    /** Streams the staged entries into [out] under the package folder, in [ExportReport.entries] order. */
    fun zipTo(report: ExportReport, out: OutputStream) {
        val zip = ZipOutputStream(out)
        for (path in report.entries) {
            val f = File(stagingDir, path)
            if (!f.isFile) continue
            zip.putNextEntry(ZipEntry("${report.folder}/$path"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
    }

    fun clear() {
        stagingDir.deleteRecursively()
    }

    /** Counts `<trkpt` in the staged GPX (also valid for a GPX staged by a previous attempt). */
    private fun countTrkpt(f: File): Int {
        if (!f.isFile) return 0
        var n = 0
        f.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { l -> var i = l.indexOf("<trkpt"); while (i >= 0) { n++; i = l.indexOf("<trkpt", i + 6) } } }
        return n
    }

    companion object {
        const val MANIFEST = "manifest.txt"
    }
}
