package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Position of a time instant on a track (DISENO §7.6 steps 3). */
sealed interface PositionAt {
    data class Interpolated(val lat: Double, val lon: Double, val gapMs: Long, val confidence: Double) : PositionAt
    data class Nearest(val lat: Double, val lon: Double, val gapMs: Long, val confidence: Double) : PositionAt
    data object None : PositionAt
}

/** Tunables of the linking rules; defaults are the spec's, configurable per transport (review D-R1). */
data class LinkConfig(
    val maxInterpGapMs: Long = 5 * 60_000,
    val maxNearestMs: Long = 10 * 60_000,
    val exifTrackWindowMs: Long = 2 * 60_000,
    val exifTrackMaxDistanceM: Double = 500.0,
    val segmentMarginMs: Long = 10 * 60_000,
) {
    companion object {
        /** Vehicles move far between fixes: wider EXIF-vs-track tolerance and longer interpolation gaps. */
        fun forTransport(transport: Transport?): LinkConfig = when (transport) {
            Transport.CAR, Transport.BUS, Transport.TRAIN, Transport.BOAT, Transport.MIXED ->
                LinkConfig(maxInterpGapMs = 10 * 60_000, exifTrackMaxDistanceM = 2_000.0)
            Transport.PLANE -> LinkConfig(maxInterpGapMs = 30 * 60_000, maxNearestMs = 30 * 60_000, exifTrackMaxDistanceM = 20_000.0)
            Transport.WALK, Transport.BIKE, null -> LinkConfig()
        }
    }
}

/**
 * Where the track was at [tUtcMs]. [points] must be the accepted points of one segment
 * in time order. Binary search for the first point at or after t.
 */
fun positionAt(points: List<TrackPoint>, tUtcMs: Long, config: LinkConfig = LinkConfig()): PositionAt {
    if (points.isEmpty()) return PositionAt.None
    var lo = 0
    var hi = points.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].timeUtcMs < tUtcMs) lo = mid + 1 else hi = mid
    }
    val next = points.getOrNull(lo)
    val prev = points.getOrNull(lo - 1)
    if (next != null && next.timeUtcMs == tUtcMs) return PositionAt.Interpolated(next.lat, next.lon, 0, 0.8)
    if (prev != null && next != null && next.timeUtcMs - prev.timeUtcMs <= config.maxInterpGapMs) {
        val f = (tUtcMs - prev.timeUtcMs).toDouble() / (next.timeUtcMs - prev.timeUtcMs)
        val lat = prev.lat + (next.lat - prev.lat) * f
        val lon = prev.lon + (next.lon - prev.lon) * f
        return PositionAt.Interpolated(lat, lon, maxOf(tUtcMs - prev.timeUtcMs, next.timeUtcMs - tUtcMs), 0.8)
    }
    val nearest = listOfNotNull(prev, next).minByOrNull { abs(it.timeUtcMs - tUtcMs) } ?: return PositionAt.None
    val gap = abs(nearest.timeUtcMs - tUtcMs)
    if (gap > config.maxNearestMs) return PositionAt.None
    return PositionAt.Nearest(nearest.lat, nearest.lon, gap, 0.7 * (1 - gap.toDouble() / config.maxNearestMs))
}

/** Result of linking one media item. */
data class MediaLink(
    val segmentId: String?,
    val lat: Double?,
    val lon: Double?,
    val method: LinkMethod,
    val confidence: Double,
    val timeGapMs: Long?,
    val needsReview: Boolean = false,
)

/**
 * Picks the segment whose interval (± margin) contains [takenUtcMs]; when several do
 * (an excursion inside a stage), the shortest wins (DISENO §7.6 step 1).
 */
fun segmentFor(takenUtcMs: Long, segments: List<Segment>, nowUtcMs: Long, config: LinkConfig = LinkConfig()): Segment? =
    segments.filter { s ->
        val end = s.endedUtcMs ?: nowUtcMs
        takenUtcMs >= s.startedUtcMs - config.segmentMarginMs && takenUtcMs <= end + config.segmentMarginMs
    }.minByOrNull { (it.endedUtcMs ?: nowUtcMs) - it.startedUtcMs }

/**
 * DISENO §7.6 steps 2-3. [points] are the accepted points of [segment] in time order.
 * Pure: the caller resolves segments and loads points.
 */
fun linkMedia(
    takenUtcMs: Long,
    exifLat: Double?,
    exifLon: Double?,
    segment: Segment?,
    points: List<TrackPoint>,
    config: LinkConfig = LinkConfig(),
): MediaLink {
    val segmentId = segment?.id
    if (exifLat != null && exifLon != null) {
        val near = points.filter { abs(it.timeUtcMs - takenUtcMs) <= config.exifTrackWindowMs }.minByOrNull { abs(it.timeUtcMs - takenUtcMs) }
        if (near != null) {
            val d = haversineM(exifLat, exifLon, near.lat, near.lon)
            val gap = abs(near.timeUtcMs - takenUtcMs)
            return if (d <= config.exifTrackMaxDistanceM) {
                MediaLink(segmentId, exifLat, exifLon, LinkMethod.EXIF, 1.0, gap)
            } else {
                // EXIF says one place, the track says another: another camera with a wrong clock?
                MediaLink(segmentId, exifLat, exifLon, LinkMethod.EXIF, 0.5, gap, needsReview = true)
            }
        }
        return MediaLink(segmentId, exifLat, exifLon, LinkMethod.EXIF, 0.9, null)
    }
    return when (val p = positionAt(points, takenUtcMs, config)) {
        is PositionAt.Interpolated -> MediaLink(segmentId, p.lat, p.lon, LinkMethod.TRACK_INTERPOLATED, p.confidence, p.gapMs)
        is PositionAt.Nearest -> MediaLink(segmentId, p.lat, p.lon, LinkMethod.TRACK_NEAREST, p.confidence, p.gapMs)
        PositionAt.None -> MediaLink(segmentId, null, null, LinkMethod.NONE, 0.0, null)
    }
}

/**
 * Camera clock offset (DISENO §7.6 step 4): the user placed a photo of camera C at
 * position P; find the track instant closest in space to P (ties: closest in time to
 * the photo's clock) and return `trackTime - photoTime`. Apply to all photos of C.
 */
fun cameraClockOffsetMs(points: List<TrackPoint>, placedLat: Double, placedLon: Double, photoUtcMs: Long): Long? {
    if (points.isEmpty()) return null
    val best = points.minWithOrNull(compareBy<TrackPoint> { haversineM(placedLat, placedLon, it.lat, it.lon) }.thenBy { abs(it.timeUtcMs - photoUtcMs) }) ?: return null
    return best.timeUtcMs - photoUtcMs
}

/** EXIF time resolution (DISENO §7.5 priorities 1 and 2). */
object ExifTime {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** `DateTimeOriginal` + `OffsetTimeOriginal` ("-03:00") → exact UTC ms, or null if unparseable. */
    fun withOffset(dateTimeOriginal: String?, offset: String?, subsec: String? = null): Long? {
        if (dateTimeOriginal == null || offset == null) return null
        return runCatching {
            val ldt = LocalDateTime.parse(dateTimeOriginal.trim(), fmt)
            val off = ZoneOffset.of(offset.trim())
            ldt.toInstant(off).toEpochMilli() + subsecMs(subsec)
        }.getOrNull()
    }

    /** `DateTimeOriginal` without offset: local time of the segment's zone, not of the phone now. */
    fun local(dateTimeOriginal: String?, tzId: String, subsec: String? = null): Long? {
        if (dateTimeOriginal == null) return null
        return runCatching {
            val ldt = LocalDateTime.parse(dateTimeOriginal.trim(), fmt)
            ldt.atZone(ZoneId.of(tzId)).toInstant().toEpochMilli() + subsecMs(subsec)
        }.getOrNull()
    }

    /** `GPSDateStamp` ("2026:10:12") + `GPSTimeStamp` ("18:49:25") are UTC: an exact anchor (review G4). */
    fun gpsUtc(gpsDateStamp: String?, gpsTimeStamp: String?): Long? {
        if (gpsDateStamp == null || gpsTimeStamp == null) return null
        return runCatching {
            val ldt = LocalDateTime.parse("${gpsDateStamp.trim()} ${gpsTimeStamp.trim().substringBefore('.')}", fmt)
            ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun subsecMs(subsec: String?): Long {
        val s = subsec?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return 0
        return (s.take(3).padEnd(3, '0')).toLong()
    }
}
