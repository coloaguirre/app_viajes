package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.geo.TimeInterval
import com.bitacoraviaje.core.geo.computeSegmentStats
import com.bitacoraviaje.core.geo.detectStops
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.TripStatus
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** What an external GPX became, plus what the user must be told (DISENO §12.5). */
data class GpxImport(val trip: TripExport, val warnings: List<String>)

/**
 * External GPX (another app, a GPS watch) as a new trip: each `<trk>` is a segment,
 * each `<trkseg>` boundary a pause, each `<wpt>` a marker. SAX so the same code runs
 * on the JVM and on Android (no StAX there). Tolerant: files without `<time>` get
 * synthetic timestamps one second apart from [importUtcMs] and a warning, because
 * without real times photos cannot be linked.
 */
object GpxReader {
    fun read(input: InputStream, fileName: String, tzId: String, importUtcMs: Long = System.currentTimeMillis()): GpxImport {
        val h = Handler()
        try {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(input, h)
        } catch (e: Exception) {
            throw ImportException("GPX ilegible: ${e.message}", e)
        }
        if (h.tracks.isEmpty() || h.tracks.all { t -> t.segs.all { it.isEmpty() } }) throw ImportException("El GPX no tiene puntos de recorrido")
        val warnings = ArrayList<String>()
        val tripId = UUID.randomUUID().toString()
        val title = h.metaName ?: h.tracks.firstOrNull()?.name ?: fileName.substringBeforeLast('.')
        var clock = importUtcMs
        val segments = h.tracks.filter { t -> t.segs.any { it.isNotEmpty() } }.mapIndexed { i, t ->
            val sid = UUID.randomUUID().toString()
            var timeless = false
            val pts = ArrayList<TrackPoint>()
            val pauses = ArrayList<SegmentPause>()
            t.segs.filter { it.isNotEmpty() }.forEachIndexed { si, seg ->
                if (si > 0 && pts.isNotEmpty()) {
                    val prev = pts.last().timeUtcMs
                    val first = seg.first().time ?: (clock + 1000)
                    pauses += SegmentPause(UUID.randomUUID().toString(), sid, PauseKind.USER, prev, maxOf(first, prev))
                }
                for (p in seg) {
                    val real = p.time
                    val t0 = real ?: run { timeless = true; clock += 1000; clock }
                    if (real != null) clock = maxOf(clock, real)
                    pts += TrackPoint(segmentId = sid, timeUtcMs = t0, lat = p.lat, lon = p.lon, altM = p.ele, speedMps = p.speed, hAccuracyM = p.hacc, provider = "import")
                }
            }
            pts.sortBy { it.timeUtcMs }
            val transport = transportOf(t.type)
            val start = pts.first().timeUtcMs
            val end = pts.last().timeUtcMs
            val stats = computeSegmentStats(pts, pauses.map { TimeInterval(it.pausedUtcMs, it.resumedUtcMs ?: end) }, transport, totalTimeMs = end - start)
            val stops = detectStops(pts).map { Stop(UUID.randomUUID().toString(), sid, it.startUtcMs, it.endUtcMs, it.lat, it.lon, it.radiusM) }
            if (timeless) warnings += "La etapa ${i + 1} no tenía horas: se asignaron horas ficticias y no se podrán vincular fotos."
            val segment = Segment(
                id = sid, tripId = tripId, type = if (i == 0) SegmentType.TRIP_START else SegmentType.STAGE, transport = transport, title = t.name,
                startedUtcMs = start, endedUtcMs = end, tzId = tzId, samplingProfile = if (transport == null || transport == Transport.WALK) "WALK" else "VEHICLE",
                recordingMode = RecordingMode.FULL, status = SegmentStatus.FINISHED, stats = stats,
            )
            SegmentExport(segment, pts, pauses, stops)
        }
        // Waypoints become markers on the segment whose time span contains them (else the nearest).
        val markers = h.waypoints.map { w ->
            val t = w.time ?: segments.first().segment.startedUtcMs
            val seg = segments.firstOrNull { t in it.segment.startedUtcMs..(it.segment.endedUtcMs ?: Long.MAX_VALUE) } ?: segments.minByOrNull { kotlin.math.abs(it.segment.startedUtcMs - t) }!!
            seg.segment.id to TextNote(UUID.randomUUID().toString(), seg.segment.id, t, w.lat, w.lon, TextNoteKind.MARKER, listOfNotNull(w.name, w.desc).joinToString(" — ").ifBlank { null })
        }
        val withMarkers = segments.map { se -> se.copy(textNotes = markers.filter { it.first == se.segment.id }.map { it.second }) }
        if (h.waypoints.isNotEmpty()) warnings += "${h.waypoints.size} waypoints importados como lugares marcados."
        val trip = Trip(id = tripId, title = title, description = h.metaDesc, createdUtcMs = importUtcMs, startedUtcMs = segments.first().segment.startedUtcMs, endedUtcMs = segments.last().segment.endedUtcMs, status = TripStatus.FINISHED)
        return GpxImport(TripExport(trip, withMarkers, appVersion = "import", exportedUtcMs = importUtcMs), warnings)
    }

    private fun transportOf(type: String?): Transport? = when (type?.lowercase()) {
        null -> null
        "hiking", "walking", "running", "trail" -> Transport.WALK
        "cycling", "biking", "mtb" -> Transport.BIKE
        "driving", "car" -> Transport.CAR
        "train" -> Transport.TRAIN
        "boat", "sailing" -> Transport.BOAT
        "flight", "plane" -> Transport.PLANE
        else -> null
    }

    private class Pt(val lat: Double, val lon: Double) {
        var ele: Double? = null
        var time: Long? = null
        var speed: Float? = null
        var hacc: Float? = null
    }

    private class Wpt(val lat: Double, val lon: Double) {
        var time: Long? = null
        var name: String? = null
        var desc: String? = null
    }

    private class Trk {
        var name: String? = null
        var type: String? = null
        val segs = ArrayList<ArrayList<Pt>>()
    }

    private class Handler : DefaultHandler() {
        var metaName: String? = null
        var metaDesc: String? = null
        val tracks = ArrayList<Trk>()
        val waypoints = ArrayList<Wpt>()
        private var trk: Trk? = null
        private var seg: ArrayList<Pt>? = null
        private var pt: Pt? = null
        private var wpt: Wpt? = null
        private var inMetadata = false
        private val text = StringBuilder()
        private val path = ArrayList<String>()

        override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
            path += localName
            text.setLength(0)
            when (localName) {
                "metadata" -> inMetadata = true
                "trk" -> trk = Trk().also { tracks += it }
                "trkseg" -> seg = ArrayList<Pt>().also { trk?.segs?.add(it) }
                "trkpt" -> pt = Pt(attributes.getValue("lat").toDouble(), attributes.getValue("lon").toDouble())
                "wpt" -> wpt = Wpt(attributes.getValue("lat").toDouble(), attributes.getValue("lon").toDouble())
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String, qName: String) {
            val v = text.toString().trim()
            val parent = path.getOrNull(path.size - 2)
            when (localName) {
                "metadata" -> inMetadata = false
                "name" -> when {
                    inMetadata -> metaName = v.ifBlank { null }
                    parent == "trk" -> trk?.name = v.ifBlank { null }
                    parent == "wpt" -> wpt?.name = v.ifBlank { null }
                }
                "desc" -> when {
                    inMetadata -> metaDesc = v.ifBlank { null }
                    parent == "wpt" -> wpt?.desc = v.ifBlank { null }
                }
                "type" -> if (parent == "trk") trk?.type = v.ifBlank { null }
                "ele" -> if (parent == "trkpt") pt?.ele = v.toDoubleOrNull()
                "time" -> when (parent) {
                    "trkpt" -> pt?.time = parseTime(v)
                    "wpt" -> wpt?.time = parseTime(v)
                }
                "speed" -> if (parent == "extensions") pt?.speed = v.toFloatOrNull()
                "hacc" -> if (parent == "extensions") pt?.hacc = v.toFloatOrNull()
                "trkpt" -> { pt?.let { seg?.add(it) }; pt = null }
                "wpt" -> { wpt?.let { waypoints += it }; wpt = null }
                "trkseg" -> seg = null
                "trk" -> trk = null
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }

        private fun parseTime(v: String): Long? = runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
    }
}
