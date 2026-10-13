package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.TextNoteKind
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * GPX 1.1 (DISENO §12.3): one `<trk>` per segment, a new `<trkseg>` after every pause,
 * photos/notes/stops as `<wpt>`. Element order follows the XSD (metadata, wpt*, trk*);
 * inside `trkpt`: ele, time, extensions. Validated against the official XSD in tests.
 */
class GpxWriter(private val segmentIndices: List<Int>? = null, override val path: String = "tracks/viaje-completo.gpx") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        val x = XmlOut(w)
        val segs = trip.segments.filterIndexed { i, _ -> segmentIndices == null || i in segmentIndices }
        x.prolog()
        x.open(
            "gpx", "version" to "1.1", "creator" to "Bitacora de Viaje ${trip.appVersion}",
            "xmlns" to NS, "xmlns:bv" to NS_BV, "xmlns:xsi" to "http://www.w3.org/2001/XMLSchema-instance",
            "xsi:schemaLocation" to "$NS http://www.topografix.com/GPX/1/1/gpx.xsd",
        )
        x.open("metadata")
        x.leaf("name", trip.trip.title)
        x.leaf("desc", trip.trip.description)
        x.leaf("time", Iso.utc(trip.exportedUtcMs))
        x.close()
        for (se in segs) {
            val z = se.segment.tzId
            for (m in se.media) {
                val la = m.linkedLat ?: continue
                val lo = m.linkedLon ?: continue
                x.open("wpt", "lat" to Num.coord(la), "lon" to Num.coord(lo))
                x.leaf("time", Iso.utc(m.takenUtcMs))
                x.leaf("name", m.userCaption ?: (if (m.kind == MediaKind.VIDEO) "Video" else "Foto"))
                x.leaf("desc", listOfNotNull(m.displayName, "hora local ${Iso.localTime(m.takenUtcMs, z)}").joinToString(" · "))
                trip.mediaFiles[m.id]?.let { x.empty("link", "href" to "../$it") }
                x.leaf("type", if (m.kind == MediaKind.VIDEO) "video" else "photo")
                x.close()
            }
            for (v in se.voiceNotes) {
                val la = v.lat ?: continue
                val lo = v.lon ?: continue
                x.open("wpt", "lat" to Num.coord(la), "lon" to Num.coord(lo))
                x.leaf("time", Iso.utc(v.startedUtcMs))
                x.leaf("name", "Nota de voz")
                x.leaf("desc", v.displayText ?: "(sin transcripción)")
                trip.audioFiles[v.id]?.let { x.empty("link", "href" to "../$it") }
                x.leaf("type", "voice")
                x.close()
            }
            for (n in se.textNotes) {
                val la = n.lat ?: continue
                val lo = n.lon ?: continue
                x.open("wpt", "lat" to Num.coord(la), "lon" to Num.coord(lo))
                x.leaf("time", Iso.utc(n.timeUtcMs))
                x.leaf("name", when (n.kind) { TextNoteKind.HIGHLIGHT -> "Momento destacado"; TextNoteKind.MARKER -> "Lugar marcado"; TextNoteKind.TRANSPORT_CHANGE -> "Cambio de medio"; TextNoteKind.NOTE -> "Nota" })
                x.leaf("desc", n.text)
                x.leaf("type", n.kind.name.lowercase())
                x.close()
            }
            for (st in se.stops) {
                x.open("wpt", "lat" to Num.coord(st.lat), "lon" to Num.coord(st.lon))
                x.leaf("time", Iso.utc(st.startUtcMs))
                x.leaf("name", st.userLabel ?: "Parada")
                x.leaf("desc", "${(st.endUtcMs - st.startUtcMs) / 60_000} min, ${Iso.localTime(st.startUtcMs, z)}–${Iso.localTime(st.endUtcMs, z)}")
                x.leaf("type", "stop")
                x.close()
            }
        }
        for (se in segs) {
            val s = se.segment
            x.open("trk")
            x.leaf("name", s.title ?: "${Iso.localDate(s.startedUtcMs, s.tzId)} · ${s.type.name.lowercase()}")
            x.leaf("type", gpxType(se))
            val pts = se.points.filter { trip.includeFilteredPoints || !it.isFilteredOut }
            val cuts = se.pauses.map { it.pausedUtcMs }.sorted()
            var cutIdx = 0
            var open = false
            for (p in pts) {
                while (cutIdx < cuts.size && cuts[cutIdx] <= p.timeUtcMs) {
                    if (open) {
                        x.close()
                        open = false
                    }
                    cutIdx++
                }
                if (!open) {
                    x.open("trkseg")
                    open = true
                }
                x.open("trkpt", "lat" to Num.coord(p.lat), "lon" to Num.coord(p.lon))
                p.altM?.let { x.leaf("ele", Num.ele(it)) }
                x.leaf("time", Iso.utc(p.timeUtcMs))
                p.hAccuracyM?.let { x.leaf("hdop", Num.f1(it.toDouble() / HDOP_SCALE_M)) }
                if (p.speedMps != null || p.hAccuracyM != null || p.isFilteredOut) {
                    x.open("extensions")
                    p.speedMps?.let { x.leaf("bv:speed", Num.f2(it.toDouble())) }
                    p.hAccuracyM?.let { x.leaf("bv:hacc", Num.f1(it.toDouble())) }
                    if (p.isFilteredOut) x.leaf("bv:filtered", "true")
                    x.close()
                }
                x.close()
            }
            if (open) x.close()
            x.close()
        }
        x.close()
        w.flush()
    }

    private fun gpxType(se: SegmentExport): String = when (se.segment.transport) {
        com.bitacoraviaje.core.model.Transport.BIKE -> "cycling"
        com.bitacoraviaje.core.model.Transport.CAR, com.bitacoraviaje.core.model.Transport.BUS -> "driving"
        com.bitacoraviaje.core.model.Transport.TRAIN -> "train"
        com.bitacoraviaje.core.model.Transport.BOAT -> "boat"
        com.bitacoraviaje.core.model.Transport.PLANE -> "flight"
        else -> if (se.segment.type == com.bitacoraviaje.core.model.SegmentType.TREKKING) "hiking" else "walking"
    }

    companion object {
        const val NS = "http://www.topografix.com/GPX/1/1"
        const val NS_BV = "https://bitacoraviaje.app/gpx/1"

        /** Rough HDOP from horizontal accuracy: 5 m of accuracy ≈ 1.0 (consumer GPS convention). */
        const val HDOP_SCALE_M = 5.0
    }
}
