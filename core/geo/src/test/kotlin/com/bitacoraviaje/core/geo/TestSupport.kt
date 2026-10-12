package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint
import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/** Minimal GPX 1.1 reader for fixtures (the real importer lives in core:export, Phase 3). */
object GpxFixture {
    fun read(resource: String, segmentId: String = "fx"): List<TrackPoint> {
        val stream: InputStream = checkNotNull(GpxFixture::class.java.getResourceAsStream(resource)) { "missing fixture $resource" }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(stream)
        val nodes = doc.getElementsByTagNameNS("*", "trkpt")
        return (0 until nodes.length).map { i ->
            val e = nodes.item(i) as org.w3c.dom.Element
            val ele = e.getElementsByTagNameNS("*", "ele").item(0)?.textContent?.toDouble()
            val time = Instant.parse(e.getElementsByTagNameNS("*", "time").item(0).textContent).toEpochMilli()
            TrackPoint(segmentId = segmentId, timeUtcMs = time, lat = e.getAttribute("lat").toDouble(), lon = e.getAttribute("lon").toDouble(), altM = ele)
        }
    }

    fun referenceDouble(resource: String, key: String): Double {
        val text = checkNotNull(GpxFixture::class.java.getResourceAsStream(resource)).bufferedReader().readText()
        val m = Regex(""""$key":\s*([-\d.]+)""").find(text) ?: error("$key not in $resource")
        return m.groupValues[1].toDouble()
    }
}

/** Builds a straight walk: one fix every [stepS] seconds, [stepM] metres north each. */
fun straightWalk(n: Int, stepM: Double = 5.5, stepS: Int = 5, lat0: Double = -34.6, lon0: Double = -58.4, t0: Long = 1_700_000_000_000L): List<TrackPoint> =
    (0 until n).map { i -> TrackPoint(segmentId = "s", timeUtcMs = t0 + i * stepS * 1000L, lat = lat0 + i * stepM / 111_320.0, lon = lon0, hAccuracyM = 5f) }

/** Runs the online filter over [raw] and returns the accepted points (reinstated jumps included). */
fun acceptedBy(config: FilterConfig, raw: List<TrackPoint>): Pair<List<TrackPoint>, Double> {
    val filter = PointFilter(config)
    val out = ArrayList<TrackPoint>()
    var dist = 0.0
    for (p in raw) {
        val v = filter.offer(p)
        if (v.accepted) {
            out += v.reinstated
            out += p
            dist += v.addedDistanceM
        }
    }
    return out to dist
}
