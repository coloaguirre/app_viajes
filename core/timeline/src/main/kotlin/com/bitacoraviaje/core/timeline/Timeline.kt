package com.bitacoraviaje.core.timeline

import com.bitacoraviaje.core.geo.PositionAt
import com.bitacoraviaje.core.geo.TimeInterval
import com.bitacoraviaje.core.geo.computeSegmentStats
import com.bitacoraviaje.core.geo.haversineM
import com.bitacoraviaje.core.geo.positionAt
import com.bitacoraviaje.core.model.DayBoundary
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.VoiceNote

/** One row of the unified timeline (DISENO §9). Times are UTC ms; the UI formats them in the segment's zone. */
sealed interface TimelineEvent {
    val timeUtcMs: Long
    val lat: Double?
    val lon: Double?

    data class SegmentStart(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val segment: Segment) : TimelineEvent
    data class SegmentEnd(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val segment: Segment) : TimelineEvent
    data class Pause(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val kind: PauseKind) : TimelineEvent
    data class Resume(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val kind: PauseKind, val pausedMs: Long) : TimelineEvent
    data class StopArrived(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val stop: Stop) : TimelineEvent
    data class StopLeft(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val stop: Stop, val dwellMs: Long) : TimelineEvent

    /** Photos/videos within 2 min and 100 m collapse into one "moment" with a carousel. */
    data class Moment(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val media: List<MediaItem>) : TimelineEvent {
        val isSingle: Boolean get() = media.size == 1
        val hasVideo: Boolean get() = media.any { it.kind == MediaKind.VIDEO }
    }

    data class Voice(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val note: VoiceNote) : TimelineEvent
    data class Text(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val note: TextNote) : TimelineEvent
    data class Highlight(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val note: TextNote) : TimelineEvent

    /** The user switched means of transport mid-segment (PLAN item 2.9); [transport] is null if the stored name is unknown. */
    data class TransportChange(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val transport: Transport?, val note: TextNote) : TimelineEvent
    data class DayChange(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val localDate: java.time.LocalDate) : TimelineEvent
    /** A place the enrichment found for this segment (DISENO §9 `PlaceVisited`), at the moment it was first seen. */
    data class PlaceVisited(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val place: Place, val visit: SegmentPlace) : TimelineEvent

    /** "You covered 12.4 km in 2 h 10 min" between two events (DISENO §9). */
    data class Summary(override val timeUtcMs: Long, override val lat: Double?, override val lon: Double?, val distanceM: Double, val durationMs: Long) : TimelineEvent
}

data class TimelineInput(
    val segment: Segment,
    val points: List<TrackPoint>,
    val pauses: List<SegmentPause> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val voiceNotes: List<VoiceNote> = emptyList(),
    val textNotes: List<TextNote> = emptyList(),
    /** Places linked to this segment (Phase 4), with their visit row. */
    val places: List<Pair<Place, SegmentPlace>> = emptyList(),
    /** For an open segment: where "now" is. */
    val nowUtcMs: Long? = null,
)

data class TimelineConfig(
    val momentWindowMs: Long = 2 * 60_000,
    val momentRadiusM: Double = 100.0,
    /** Minimum leg between events worth a summary row. */
    val summaryMinDistanceM: Double = 300.0,
    val summaryMinDurationMs: Long = 10 * 60_000,
)

/**
 * Pure builder: merges the segment lifecycle, pauses, stops, media moments, notes and
 * day changes into one time-ordered list, and inserts travelled-distance summaries
 * between events when the leg is meaningful. Events without a position borrow it from
 * the track at their time.
 */
fun buildTimeline(input: TimelineInput, config: TimelineConfig = TimelineConfig()): List<TimelineEvent> {
    val s = input.segment
    val pts = input.points
    val end = s.endedUtcMs ?: input.nowUtcMs ?: pts.lastOrNull()?.timeUtcMs ?: s.startedUtcMs
    fun at(t: Long): Pair<Double?, Double?> = when (val p = positionAt(pts, t)) {
        is PositionAt.Interpolated -> p.lat to p.lon
        is PositionAt.Nearest -> p.lat to p.lon
        PositionAt.None -> null to null
    }

    val events = ArrayList<TimelineEvent>()
    at(s.startedUtcMs).let { (la, lo) -> events += TimelineEvent.SegmentStart(s.startedUtcMs, la, lo, s) }
    for (p in input.pauses) {
        at(p.pausedUtcMs).let { (la, lo) -> events += TimelineEvent.Pause(p.pausedUtcMs, la, lo, p.kind) }
        p.resumedUtcMs?.let { r -> at(r).let { (la, lo) -> events += TimelineEvent.Resume(r, la, lo, p.kind, r - p.pausedUtcMs) } }
    }
    for (st in input.stops) {
        events += TimelineEvent.StopArrived(st.startUtcMs, st.lat, st.lon, st)
        events += TimelineEvent.StopLeft(st.endUtcMs, st.lat, st.lon, st, st.endUtcMs - st.startUtcMs)
    }
    events += groupMoments(input.media.filter { !it.isExcluded }.sortedBy { it.takenUtcMs }, config, ::at)
    for (v in input.voiceNotes) {
        val (la, lo) = if (v.lat != null && v.lon != null) v.lat to v.lon else at(v.startedUtcMs)
        events += TimelineEvent.Voice(v.startedUtcMs, la, lo, v)
    }
    for (n in input.textNotes) {
        val (la, lo) = if (n.lat != null && n.lon != null) n.lat to n.lon else at(n.timeUtcMs)
        events += when (n.kind) {
            TextNoteKind.HIGHLIGHT -> TimelineEvent.Highlight(n.timeUtcMs, la, lo, n)
            TextNoteKind.TRANSPORT_CHANGE -> TimelineEvent.TransportChange(n.timeUtcMs, la, lo, n.text?.let { t -> Transport.entries.firstOrNull { it.name == t } }, n)
            TextNoteKind.NOTE, TextNoteKind.MARKER -> TimelineEvent.Text(n.timeUtcMs, la, lo, n)
        }
    }
    for ((place, visit) in input.places) {
        val t = visit.firstSeenUtcMs.coerceIn(s.startedUtcMs, end)
        events += TimelineEvent.PlaceVisited(t, place.lat, place.lon, place, visit)
    }
    // Day separators at every local midnight crossed between start and end.
    var midnight = DayBoundary.nextLocalMidnightUtcMs(s.startedUtcMs, s.tzId)
    while (midnight < end) {
        at(midnight).let { (la, lo) -> events += TimelineEvent.DayChange(midnight, la, lo, DayBoundary.localDate(midnight, s.tzId)) }
        midnight = DayBoundary.nextLocalMidnightUtcMs(midnight, s.tzId)
    }
    s.endedUtcMs?.let { endedAt -> at(endedAt).let { (la, lo) -> events += TimelineEvent.SegmentEnd(endedAt, la, lo, s) } }

    val ordered = events.sortedWith(compareBy<TimelineEvent> { it.timeUtcMs }.thenBy { rank(it) })
    return withSummaries(ordered, pts, input.pauses, config)
}

private fun rank(e: TimelineEvent): Int = when (e) {
    is TimelineEvent.SegmentStart -> 0
    is TimelineEvent.DayChange -> 1
    is TimelineEvent.Resume -> 2
    is TimelineEvent.StopArrived -> 3
    is TimelineEvent.Moment, is TimelineEvent.Voice, is TimelineEvent.Text, is TimelineEvent.Highlight, is TimelineEvent.TransportChange -> 4
    is TimelineEvent.PlaceVisited -> 5
    is TimelineEvent.StopLeft -> 6
    is TimelineEvent.Pause -> 7
    is TimelineEvent.Summary -> 8
    is TimelineEvent.SegmentEnd -> 9
}

private fun groupMoments(media: List<MediaItem>, config: TimelineConfig, at: (Long) -> Pair<Double?, Double?>): List<TimelineEvent.Moment> {
    val out = ArrayList<TimelineEvent.Moment>()
    var group = ArrayList<MediaItem>()
    fun pos(m: MediaItem): Pair<Double?, Double?> = if (m.linkedLat != null && m.linkedLon != null) m.linkedLat to m.linkedLon else at(m.takenUtcMs)
    fun flush() {
        if (group.isEmpty()) return
        val first = group.first()
        val (la, lo) = pos(first)
        out += TimelineEvent.Moment(first.takenUtcMs, la, lo, group.toList())
        group = ArrayList()
    }
    for (m in media) {
        val first = group.firstOrNull()
        if (first == null) {
            group += m
            continue
        }
        val (la1, lo1) = pos(first)
        val (la2, lo2) = pos(m)
        val close = la1 != null && lo1 != null && la2 != null && lo2 != null && haversineM(la1, lo1, la2, lo2) <= config.momentRadiusM
        val soon = m.takenUtcMs - first.takenUtcMs <= config.momentWindowMs
        if (soon && (close || la1 == null || la2 == null)) group += m else { flush(); group += m }
    }
    flush()
    return out
}

/** Inserts a Summary before an event when the leg since the previous event is long enough. */
private fun withSummaries(events: List<TimelineEvent>, pts: List<TrackPoint>, pauses: List<SegmentPause>, config: TimelineConfig): List<TimelineEvent> {
    if (events.size < 2 || pts.size < 2) return events
    val out = ArrayList<TimelineEvent>(events.size + 8)
    var prev = events.first()
    out += prev
    val pauseIntervals = pauses.map { TimeInterval(it.pausedUtcMs, it.resumedUtcMs ?: Long.MAX_VALUE) }
    for (e in events.drop(1)) {
        val leg = pts.filter { it.timeUtcMs in prev.timeUtcMs..e.timeUtcMs }
        if (leg.size >= 2) {
            val stats = computeSegmentStats(leg, pauseIntervals)
            val d = stats.distanceM ?: 0.0
            val dur = e.timeUtcMs - prev.timeUtcMs
            if (d >= config.summaryMinDistanceM && dur >= config.summaryMinDurationMs) {
                out += TimelineEvent.Summary(e.timeUtcMs - 1, e.lat, e.lon, d, dur)
            }
        }
        out += e
        prev = e
    }
    return out
}
