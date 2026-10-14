package com.bitacoraviaje.core.export

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opens the bytes of a media or audio file for the package; null when the file is gone. */
fun interface FileSource {
    fun open(relativePath: String): InputStream?
}

/** What ended up in the ZIP against what the database holds (reconciliation, PLAN 3.1). */
data class ExportReport(
    val folder: String,
    val entries: List<String>,
    val pointsInDb: Int,
    val pointsWritten: Int,
    val mediaInDb: Int,
    val mediaWritten: Int,
    val mediaMissing: List<String>,
    val voiceNotesInDb: Int,
    val audioWritten: Int,
    val audioMissing: List<String>,
    val textNotesInDb: Int,
    val stopsInDb: Int,
    /** What the privacy zones removed, when they were applied (PLAN 5.5). */
    val privacy: PrivacyReport? = null,
) {
    val complete: Boolean get() = mediaMissing.isEmpty() && audioMissing.isEmpty()
}

/**
 * The ZIP of DISENO §12.1, written in streaming order: documents first, then tracks and
 * tables, then every media and audio file the caller can open, and `LEEME.txt` last
 * with the counts and whatever could not be included. Nothing is buffered whole in
 * memory except the small documents.
 */
class ExportPackage(
    private val thumbnails: Map<String, ByteArray> = emptyMap(),
    private val includeMedia: Boolean = true,
    private val includeAudio: Boolean = true,
) {
    fun write(trip: TripExport, out: OutputStream, files: FileSource, privacy: PrivacyReport? = null, onProgress: ((done: Int, total: Int) -> Unit)? = null): ExportReport {
        val folder = Names.packageFolder(trip.trip.title, trip.exportedUtcMs, trip.tzId)
        val zip = ZipOutputStream(out)
        val entries = ArrayList<String>()
        fun entry(path: String, body: (OutputStream) -> Unit) {
            zip.putNextEntry(ZipEntry("$folder/$path"))
            body(NonClosing(zip))
            zip.closeEntry()
            entries += path
        }
        val writers = writers(trip, thumbnails)
        val mediaPaths = if (includeMedia) trip.mediaFiles else emptyMap()
        val audioPaths = if (includeAudio) trip.audioFiles else emptyMap()
        val total = writers.size + mediaPaths.size + audioPaths.size + 1
        var done = 0
        for (wr in writers) {
            entry(wr.path) { wr.write(trip, it) }
            onProgress?.invoke(++done, total)
        }
        val mediaMissing = ArrayList<String>()
        for ((id, path) in mediaPaths) {
            val ins = files.open(path)
            if (ins == null) {
                mediaMissing += "$path (id $id)"
            } else {
                entry(path) { o -> ins.use { it.copyTo(o) } }
            }
            onProgress?.invoke(++done, total)
        }
        val audioMissing = ArrayList<String>()
        for ((id, path) in audioPaths) {
            val ins = files.open(path)
            if (ins == null) {
                audioMissing += "$path (nota $id)"
            } else {
                entry(path) { o -> ins.use { it.copyTo(o) } }
            }
            onProgress?.invoke(++done, total)
        }
        val report = ExportReport(
            folder = folder,
            entries = entries + "LEEME.txt",
            pointsInDb = trip.segments.sumOf { se -> se.points.count { trip.includeFilteredPoints || !it.isFilteredOut } },
            pointsWritten = writers.filterIsInstance<GpxWriter>().firstOrNull { it.path == "tracks/viaje-completo.gpx" }?.pointsWritten ?: 0,
            mediaInDb = trip.allMedia.size,
            mediaWritten = mediaPaths.size - mediaMissing.size,
            mediaMissing = mediaMissing,
            voiceNotesInDb = trip.allVoiceNotes.size,
            audioWritten = audioPaths.size - audioMissing.size,
            audioMissing = audioMissing,
            textNotesInDb = trip.allTextNotes.size,
            stopsInDb = trip.allStops.size,
            privacy = privacy,
        )
        entry("LEEME.txt") { it.write(LeemeWriter.text(trip, report, includeMedia, includeAudio).toByteArray(Charsets.UTF_8)) }
        onProgress?.invoke(++done, total)
        zip.finish()
        return report
    }

    companion object {
        /** The document writers of a package, in ZIP order (DISENO §12.1). */
        fun writers(trip: TripExport, thumbnails: Map<String, ByteArray>): List<TripWriter> = buildList {
            add(TripJsonWriter())
            add(MarkdownWriter())
            add(HtmlWriter())
            add(GpxWriter())
            trip.segments.forEachIndexed { i, se -> add(GpxWriter(listOf(i), "tracks/" + Names.segmentFile(i + 1, se.segment.startedUtcMs, se.segment.tzId, se.segment.title, "gpx"))) }
            add(KmlWriter())
            add(KmzWriter(thumbnails))
            add(GeoJsonWriter())
            add(PointsCsvWriter())
            add(MediaCsvWriter())
            add(NotesCsvWriter())
            add(PlacesCsvWriter())
            add(WeatherCsvWriter())
            add(SrtWriter())
        }
    }

    /** ZipOutputStream must stay open between entries; writers may close what they get. */
    private class NonClosing(private val z: ZipOutputStream) : OutputStream() {
        override fun write(b: Int) = z.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = z.write(b, off, len)
        override fun flush() = z.flush()
        override fun close() = z.flush()
    }
}

object LeemeWriter {
    fun text(trip: TripExport, r: ExportReport, includeMedia: Boolean, includeAudio: Boolean): String = buildString {
        appendLine("BITÁCORA DE VIAJE — ${trip.trip.title}")
        appendLine("Exportado el ${Iso.local(trip.exportedUtcMs, trip.tzId)} con la versión ${trip.appVersion}.")
        appendLine()
        appendLine("CÓMO ABRIR CADA ARCHIVO")
        appendLine("- bitacora.html: el diario con mapa. Doble clic; se abre en el navegador (necesita internet para el fondo del mapa).")
        appendLine("- bitacora.md: el mismo diario en texto (Markdown).")
        appendLine("- trip.json: todos los datos del viaje, para el renderizador de video y para volver a importar en la app.")
        appendLine("- tracks/viaje-completo.gpx y tracks/01_….gpx: el recorrido para Garmin, Strava, Wikiloc, QGIS…")
        appendLine("- tracks/viaje.kml: para Google My Maps (Importar) y Google Earth. tracks/viaje.kmz: lo mismo con miniaturas, para Google Earth.")
        appendLine("- tracks/viaje.geojson: para herramientas de mapas (QGIS, geojson.io).")
        appendLine("- tablas/*.csv: planillas (Excel, LibreOffice) en UTF-8; puntos, medios, notas y lugares.")
        appendLine("- media/ y audio/: tus fotos, videos y notas de voz originales, por fecha.")
        appendLine("- subtitulos/notas.srt: las notas de voz como subtítulos, con la hora relativa al inicio de la etapa.")
        appendLine()
        appendLine("CONTENIDO")
        appendLine("- Etapas: ${trip.segments.size}")
        appendLine("- Puntos del recorrido: ${r.pointsWritten} (de ${r.pointsInDb} en la base${if (trip.includeFilteredPoints) ", incluye los filtrados" else ", sin los filtrados"})")
        appendLine("- Fotos y videos: ${r.mediaWritten} copiados de ${r.mediaInDb} registrados${if (!includeMedia) " (no se incluyeron por elección)" else ""}")
        appendLine("- Notas de voz: ${r.audioWritten} audios de ${r.voiceNotesInDb} notas${if (!includeAudio) " (no se incluyeron por elección)" else ""}")
        appendLine("- Notas de texto, marcadores y destacados: ${r.textNotesInDb}")
        appendLine("- Paradas detectadas: ${r.stopsInDb}")
        r.privacy?.let { p ->
            appendLine()
            appendLine("ZONAS DE PRIVACIDAD (${p.zones} aplicadas al exportar; la base del teléfono conserva todo)")
            appendLine("- Puntos del recorrido omitidos: ${p.pointsRemoved} (el recorrido se corta ${p.cuts} ${if (p.cuts == 1) "vez" else "veces"})")
            appendLine("- Paradas omitidas: ${p.stopsRemoved} · Lugares omitidos: ${p.placesRemoved}")
            appendLine("- Fotos y videos sin ubicación en este paquete: ${p.mediaBlanked} · Notas sin ubicación: ${p.notesBlanked}")
            when {
                p.exifStripped != null -> appendLine("- Copias JPEG verificadas sin GPS en su EXIF: ${p.exifStripped} (los originales del teléfono no se tocan)")
                p.mediaBlanked > 0 -> appendLine("- Atención: las copias JPEG conservan su propio EXIF (no se eligió quitarles el GPS); las coordenadas de arriba sí fueron omitidas.")
            }
            if (p.mediaWithMetadata.isNotEmpty()) {
                appendLine("- Atención: ${p.mediaWithMetadata.size} copias de adentro de una zona conservan la ubicación en sus propios metadatos (HEIC, video o reescritura fallida); revisalas antes de compartir:")
                p.mediaWithMetadata.forEach { appendLine("    · $it") }
            }
            appendLine("- Distancia y tiempos de cada etapa siguen siendo los medidos (incluyen los tramos omitidos); el recuadro geográfico se recalculó sobre los puntos exportados.")
        }
        if (r.mediaMissing.isNotEmpty() || r.audioMissing.isNotEmpty()) {
            appendLine()
            appendLine("FALTANTES (el archivo ya no estaba en el teléfono al exportar)")
            r.mediaMissing.forEach { appendLine("- $it") }
            r.audioMissing.forEach { appendLine("- $it") }
        }
        appendLine()
        if (trip.places.isNotEmpty()) appendLine("Lugares con información de internet: ${trip.places.size} (textos en tablas/lugares.csv y en la bitácora, cada uno con su fuente y licencia).")
        if (trip.allWeather.isNotEmpty()) appendLine("Clima: ${trip.allWeather.size} horas con datos de Open-Meteo (tablas/clima.csv; resumen por mañana, tarde y noche en la bitácora).")
        appendLine()
        appendLine("ATRIBUCIONES")
        trip.attributions.forEach { appendLine("- ${it.name} (${it.license}) ${it.url}") }
    }
}
