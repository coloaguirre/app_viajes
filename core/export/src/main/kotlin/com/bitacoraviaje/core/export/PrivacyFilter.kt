package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.geo.boundingBox
import com.bitacoraviaje.core.geo.haversineM
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.PrivacyZone
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.TrackPoint

/** What the zones removed or blanked, reported in LEEME and the export dialog (nothing is dropped silently). */
data class PrivacyReport(
    val zones: Int,
    val pointsRemoved: Int,
    val cuts: Int,
    val stopsRemoved: Int,
    val placesRemoved: Int,
    val mediaBlanked: Int,
    val notesBlanked: Int,
    /** JPEG copies verified without GPS tags by the exporter (null: the option was off). Measured, not predicted. */
    val exifStripped: Int? = null,
    /** Copies inside a zone that still carry their own location metadata (HEIC/MP4/failed rewrite): named, never silent. */
    val mediaWithMetadata: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = pointsRemoved == 0 && stopsRemoved == 0 && placesRemoved == 0 && mediaBlanked == 0 && notesBlanked == 0
}

/**
 * DISENO §15.3 / PLAN 5.5, pure: applied to the export, never to the database. Points inside a
 * zone disappear and the track is cut there (a [PauseKind.PRIVACY] pause, which every writer
 * already honours as a `<trkseg>` break); stops and places inside are dropped; media, voice and
 * text notes inside keep their row but lose lat/lon (the original files are untouched; the
 * exported copy's EXIF is the app's job). Everything removed is counted in [PrivacyReport].
 */
object PrivacyFilter {
    fun inside(lat: Double?, lon: Double?, zones: List<PrivacyZone>): Boolean =
        lat != null && lon != null && zones.any { haversineM(lat, lon, it.lat, it.lon) <= it.radiusM }

    fun apply(trip: TripExport, zones: List<PrivacyZone>): Pair<TripExport, PrivacyReport> {
        if (zones.isEmpty()) return trip to PrivacyReport(0, 0, 0, 0, 0, 0, 0)
        var pointsRemoved = 0
        var cuts = 0
        var stopsRemoved = 0
        var mediaBlanked = 0
        var notesBlanked = 0
        val removedPlaceIds = trip.places.filter { inside(it.lat, it.lon, zones) }.map { it.id }.toSet()
        val segments = trip.segments.map { se ->
            val kept = ArrayList<TrackPoint>()
            val pauses = ArrayList(se.pauses)
            var lastInside = false
            var openCut: SegmentPause? = null
            var lastRemovedMs = 0L
            for (p in se.points) {
                val hit = inside(p.lat, p.lon, zones)
                if (hit) {
                    pointsRemoved++
                    lastRemovedMs = p.timeUtcMs
                    if (!lastInside && kept.isNotEmpty()) {
                        // Entering a zone with track behind us: cut here so no straight line crosses the zone.
                        openCut = SegmentPause("privacy-${se.segment.id}-${p.timeUtcMs}", se.segment.id, PauseKind.PRIVACY, p.timeUtcMs, null)
                        cuts++
                    }
                } else {
                    // Leaving a zone closes the cut at the first kept point: "omitted from t1 to t2" (an open pause would
                    // swallow the rest of the stage in the diary summaries and import as an unfinished pause).
                    openCut?.let { pauses += it.copy(resumedUtcMs = p.timeUtcMs); openCut = null }
                    kept += p
                }
                lastInside = hit
            }
            openCut?.let { pauses += it.copy(resumedUtcMs = maxOf(lastRemovedMs, it.pausedUtcMs)) }
            val stops = se.stops.filter { !inside(it.lat, it.lon, zones) }.also { stopsRemoved += se.stops.size - it.size }
                .map { st -> if (st.placeId != null && st.placeId in removedPlaceIds) st.copy(placeId = null) else st }
            // The stored bbox was computed over every point; a trip that starts at home would still name it (review I2).
            val stats = se.segment.stats.copy(bbox = boundingBox(kept.filter { !it.isFilteredOut }.ifEmpty { kept }))
            val media = se.media.map { m ->
                if (inside(m.linkedLat, m.linkedLon, zones) || inside(m.exifLat, m.exifLon, zones)) { mediaBlanked++; m.copy(linkedLat = null, linkedLon = null, exifLat = null, exifLon = null) } else m
            }
            val voice = se.voiceNotes.map { v -> if (inside(v.lat, v.lon, zones)) { notesBlanked++; v.copy(lat = null, lon = null) } else v }
            val text = se.textNotes.map { n -> if (inside(n.lat, n.lon, zones)) { notesBlanked++; n.copy(lat = null, lon = null) } else n }
            // Weather samples sit at ~1 km, but one placed inside a zone still names the hour the user was there (backlog D3).
            val weather = se.weather.filter { !inside(it.lat, it.lon, zones) }
            se.copy(segment = se.segment.copy(stats = stats), points = kept, pauses = pauses.sortedBy { it.pausedUtcMs }, stops = stops, media = media, voiceNotes = voice, textNotes = text, weather = weather)
        }
        val enrichments = trip.enrichments.filter { it.placeId !in removedPlaceIds }
        val filtered = trip.copy(
            segments = segments,
            places = trip.places.filter { it.id !in removedPlaceIds },
            enrichments = enrichments,
            segmentPlaces = trip.segmentPlaces.filter { it.placeId !in removedPlaceIds },
            // Credits name the article (and so the place): recompute them from what is left (review I1), weather included (D3).
            attributions = TripExport.attributionsFor(enrichments, segments.any { it.weather.isNotEmpty() }),
        )
        return filtered to PrivacyReport(zones.size, pointsRemoved, cuts, stopsRemoved, removedPlaceIds.size, mediaBlanked, notesBlanked)
    }
}
