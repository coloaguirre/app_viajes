package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.videoplan.PlanInput
import com.bitacoraviaje.core.videoplan.PlanMedia
import com.bitacoraviaje.core.videoplan.PlanPlace
import com.bitacoraviaje.core.videoplan.PlanSegment
import com.bitacoraviaje.core.videoplan.PlanVoice

/** Adapter from the export model (what trip.json holds) to the planner input: the same rules on the phone and on the PC. */
object PlanInputs {
    fun of(trip: TripExport, extraCredits: List<String> = emptyList()): PlanInput = PlanInput(
        tripId = trip.trip.id,
        title = trip.trip.title,
        coverMediaId = trip.trip.coverMediaId,
        extraCredits = extraCredits,
        segments = trip.segments.sortedBy { it.segment.startedUtcMs }.map { se ->
            val s = se.segment
            PlanSegment(
                id = s.id,
                title = s.displayName ?: when (s.type.name) { "TREKKING" -> "Trekking"; "EXCURSION" -> "Excursión"; else -> "Etapa" }, // same rule as planner.py (review M8)
                date = Iso.localDate(s.startedUtcMs, s.tzId),
                startedUtcMs = s.startedUtcMs,
                endedUtcMs = s.endedUtcMs,
                distanceM = s.stats.distanceM,
                movingTimeMs = s.stats.movingTimeMs,
                elevationGainM = s.stats.elevationGainM,
                maxAltM = s.stats.maxAltM,
                hasTrack = se.points.count { !it.isFilteredOut } >= 2,
                media = se.media.sortedBy { it.takenUtcMs }.map { m -> PlanMedia(m.id, m.kind == MediaKind.VIDEO, m.takenUtcMs, m.durationMs, m.isFavorite, m.isExcluded, m.userCaption) },
                voiceNotes = se.voiceNotes.sortedBy { it.startedUtcMs }.map { v -> PlanVoice(v.id, v.startedUtcMs, v.durationMs, v.displayText) },
                highlightsUtcMs = se.textNotes.filter { it.kind == TextNoteKind.HIGHLIGHT }.map { it.timeUtcMs }.sorted(),
                weather = com.bitacoraviaje.core.timeline.WeatherDigest.segmentText(se.weather),
                // Only texts the user approved enter the video (DISENO §11.4); Wikipedia text first, then the Wikidata facts.
                places = trip.placesOf(s.id).flatMap { (p, _) ->
                    trip.enrichmentsOf(p.id).filter { it.userApproved }.sortedBy { if (it.sourceName == Enrichment.SOURCE_WIKIPEDIA) 0 else 1 }
                        .map { e -> PlanPlace(p.id, p.name, e.summary, e.sourceName, e.license, e.sourceUrl.ifBlank { null }) }
                },
            )
        },
    )
}
