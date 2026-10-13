package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.geo.simplifyIndices
import com.bitacoraviaje.core.model.MediaKind
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * KML for Google My Maps and Earth (DISENO §12.3): a Folder per segment with the track
 * simplified to [toleranceM] as a LineString (lon,lat,alt), a Style per segment, and
 * Placemarks for photos, notes and stops with plain-HTML descriptions. My Maps caps
 * layers and elements, so [maxPlacemarksPerSegment] trims the busiest segments.
 * [thumbnails] (KMZ only) are referenced as `files/<id>.jpg`.
 */
class KmlWriter(
    override val path: String = "tracks/viaje.kml",
    private val toleranceM: Double = 30.0,
    private val maxPlacemarksPerSegment: Int = MY_MAPS_MAX_PER_LAYER,
    private val thumbnails: Map<String, ByteArray> = emptyMap(),
) : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        val x = XmlOut(w)
        x.prolog()
        x.open("kml", "xmlns" to "http://www.opengis.net/kml/2.2")
        x.open("Document")
        x.leaf("name", trip.trip.title)
        trip.trip.description?.let { x.leaf("description", it) }
        trip.segments.forEachIndexed { i, _ ->
            x.open("Style", "id" to "track$i")
            x.open("LineStyle")
            x.leaf("color", COLORS[i % COLORS.size])
            x.leaf("width", "4")
            x.close()
            x.close()
        }
        x.open("Style", "id" to "photo")
        x.open("IconStyle")
        x.open("Icon")
        x.leaf("href", "https://maps.google.com/mapfiles/kml/shapes/camera.png")
        x.close()
        x.close()
        x.close()
        trip.segments.forEachIndexed { i, se ->
            val s = se.segment
            val z = s.tzId
            x.open("Folder")
            x.leaf("name", "${Iso.localDate(s.startedUtcMs, z)} · ${s.title ?: s.type.name.lowercase()}")
            val pts = se.points.filter { !it.isFilteredOut }
            if (pts.size >= 2) {
                val keep = simplifyIndices(pts, toleranceM)
                x.open("Placemark")
                x.leaf("name", s.title ?: "Recorrido")
                x.leaf("styleUrl", "#track$i")
                x.open("LineString")
                x.leaf("tessellate", "1")
                x.leaf("coordinates", keep.joinToString(" ") { k -> val p = pts[k]; "${Num.coord(p.lon)},${Num.coord(p.lat)},${Num.ele(p.altM ?: 0.0)}" })
                x.close()
                x.close()
            }
            var budget = maxPlacemarksPerSegment - 1
            fun placemark(name: String, lat: Double, lon: Double, html: String, style: String? = null) {
                if (budget-- <= 0) return
                x.open("Placemark")
                x.leaf("name", name)
                x.cdata("description", html)
                style?.let { x.leaf("styleUrl", it) }
                x.open("Point")
                x.leaf("coordinates", "${Num.coord(lon)},${Num.coord(lat)},0")
                x.close()
                x.close()
            }
            for (st in se.stops) placemark(st.userLabel ?: "Parada", st.lat, st.lon, "<p>${(st.endUtcMs - st.startUtcMs) / 60_000} min · ${Iso.localTime(st.startUtcMs, z)}–${Iso.localTime(st.endUtcMs, z)}</p>")
            for (m in se.media) {
                val la = m.linkedLat ?: continue
                val lo = m.linkedLon ?: continue
                val thumb = if (m.id in thumbnails) "<img src=\"files/${m.id}.jpg\" width=\"320\"/><br/>" else ""
                placemark(m.userCaption ?: (if (m.kind == MediaKind.VIDEO) "Video" else "Foto"), la, lo, "$thumb<p>${htmlEsc(m.displayName ?: "")} · ${Iso.localTime(m.takenUtcMs, z)}</p>", "#photo")
            }
            for (v in se.voiceNotes) {
                val la = v.lat ?: continue
                val lo = v.lon ?: continue
                placemark("Nota de voz ${Iso.localTime(v.startedUtcMs, z)}", la, lo, "<p>${htmlEsc(v.displayText ?: "(sin transcripción)")}</p>")
            }
            for (n in se.textNotes) {
                val la = n.lat ?: continue
                val lo = n.lon ?: continue
                placemark(n.text ?: n.kind.name.lowercase().replaceFirstChar { it.uppercase() }, la, lo, "<p>${Iso.localTime(n.timeUtcMs, z)}</p>")
            }
            x.close()
        }
        x.close()
        x.close()
        w.flush()
    }

    companion object {
        /** Google My Maps: 10 layers, 2,000 features per layer (verified in docs 2026-10; re-check before Fase 6). */
        const val MY_MAPS_MAX_LAYERS = 10
        const val MY_MAPS_MAX_PER_LAYER = 2_000

        /** KML colors are aabbggrr. */
        val COLORS = listOf("ff64381f", "ff1f8f64", "ff1f3fd4", "ff8f1fa4", "ff007fff", "ff4040c0")
    }
}

/** KMZ = ZIP with `doc.kml` plus `files/<mediaId>.jpg` thumbnails (Google Earth shows them; My Maps does not). */
class KmzWriter(private val thumbnails: Map<String, ByteArray>, override val path: String = "tracks/viaje.kmz") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("doc.kml"))
        val kml = java.io.ByteArrayOutputStream()
        KmlWriter(thumbnails = thumbnails).write(trip, kml)
        zip.write(kml.toByteArray())
        zip.closeEntry()
        for ((id, bytes) in thumbnails) {
            zip.putNextEntry(ZipEntry("files/$id.jpg"))
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
    }
}
