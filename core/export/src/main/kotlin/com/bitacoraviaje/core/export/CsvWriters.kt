package com.bitacoraviaje.core.export

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/** RFC 4180 rows, UTF-8 with BOM so Excel detects the encoding, `,` separator, `""` escape. */
class Csv(private val w: Writer) {
    init {
        w.write("﻿")
    }

    fun row(vararg cells: Any?) {
        w.write(cells.joinToString(",") { cell(it) })
        w.write("\r\n")
    }

    private fun cell(v: Any?): String {
        val s = when (v) {
            null -> return ""
            is Double -> if (v.isNaN()) "" else java.lang.String.format(java.util.Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')
            is Float -> java.lang.String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

private fun OutputStream.csv(): Pair<Csv, Writer> {
    val w = OutputStreamWriter(this, Charsets.UTF_8)
    return Csv(w) to w
}

class PointsCsvWriter(override val path: String = "tablas/puntos.csv") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val (c, w) = out.csv()
        c.row("segment_id", "segment_title", "time_utc", "time_local", "lat", "lon", "alt_m", "h_accuracy_m", "speed_mps", "bearing_deg", "provider", "filtered")
        trip.segments.forEach { se ->
            val z = se.segment.tzId
            se.points.filter { trip.includeFilteredPoints || !it.isFilteredOut }.forEach { p ->
                c.row(se.segment.id, se.segment.title, Iso.utc(p.timeUtcMs), Iso.local(p.timeUtcMs, z), p.lat, p.lon, p.altM, p.hAccuracyM, p.speedMps, p.bearingDeg, p.provider, if (p.isFilteredOut) 1 else 0)
            }
        }
        w.flush()
    }
}

class MediaCsvWriter(override val path: String = "tablas/medios.csv") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val (c, w) = out.csv()
        c.row("id", "segment_id", "kind", "file", "display_name", "taken_utc", "taken_local", "taken_time_source", "lat", "lon", "link_method", "link_confidence", "needs_review", "camera", "caption", "favorite", "excluded")
        trip.segments.forEach { se ->
            val z = se.segment.tzId
            se.media.forEach { m ->
                c.row(
                    m.id, se.segment.id, m.kind.name, trip.mediaFiles[m.id], m.displayName, Iso.utc(m.takenUtcMs), Iso.local(m.takenUtcMs, z), m.takenTimeSource.name,
                    m.linkedLat, m.linkedLon, m.linkMethod.name, m.linkConfidence, if (m.needsReview) 1 else 0,
                    listOfNotNull(m.cameraMake, m.cameraModel).joinToString(" ").ifBlank { null }, m.userCaption, if (m.isFavorite) 1 else 0, if (m.isExcluded) 1 else 0,
                )
            }
        }
        w.flush()
    }
}

class NotesCsvWriter(override val path: String = "tablas/notas.csv") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val (c, w) = out.csv()
        c.row("id", "segment_id", "tipo", "time_utc", "time_local", "lat", "lon", "texto", "duration_ms", "file", "transcript_status")
        trip.segments.forEach { se ->
            val z = se.segment.tzId
            se.voiceNotes.forEach { v -> c.row(v.id, se.segment.id, "voz", Iso.utc(v.startedUtcMs), Iso.local(v.startedUtcMs, z), v.lat, v.lon, v.displayText, v.durationMs, trip.audioFiles[v.id], v.transcriptStatus.name) }
            se.textNotes.forEach { n -> c.row(n.id, se.segment.id, n.kind.name.lowercase(), Iso.utc(n.timeUtcMs), Iso.local(n.timeUtcMs, z), n.lat, n.lon, n.text, null, null, null) }
        }
        w.flush()
    }
}

/** Places come with Fase 4; until then the file lists the detected stops (the input of the enrichment). */
class PlacesCsvWriter(override val path: String = "tablas/lugares.csv") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val (c, w) = out.csv()
        c.row("id", "segment_id", "tipo", "nombre", "lat", "lon", "start_utc", "start_local", "end_utc", "end_local", "minutos", "radius_m", "kind", "wikidata_id", "fuente", "resumen", "licencia", "url")
        trip.segments.forEach { se ->
            val z = se.segment.tzId
            se.stops.forEach { st -> c.row(st.id, se.segment.id, "parada", st.userLabel ?: trip.places.firstOrNull { it.id == st.placeId }?.name, st.lat, st.lon, Iso.utc(st.startUtcMs), Iso.local(st.startUtcMs, z), Iso.utc(st.endUtcMs), Iso.local(st.endUtcMs, z), (st.endUtcMs - st.startUtcMs) / 60_000, st.radiusM, null, null, null, null, null, null) }
            // DISENO §11: one row per place and text, with its license.
            trip.placesOf(se.segment.id).forEach { (p, sp) ->
                val texts = trip.enrichmentsOf(p.id).ifEmpty { listOf(null) }
                texts.forEach { e -> c.row(p.id, se.segment.id, "lugar", p.name, p.lat, p.lon, Iso.utc(sp.firstSeenUtcMs), Iso.local(sp.firstSeenUtcMs, z), null, null, sp.dwellMs?.let { it / 60_000 }, null, p.kind, p.wikidataId, e?.sourceName ?: p.source.name, e?.summary, e?.license, e?.sourceUrl) }
            }
        }
        w.flush()
    }
}
