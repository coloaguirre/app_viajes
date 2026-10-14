package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.model.WeatherSample

/** Everything the writers need for one segment, already loaded from the database. */
data class SegmentExport(
    val segment: Segment,
    /** Accepted points in time order; filtered-out ones only when the export asks for them. */
    val points: List<TrackPoint>,
    val pauses: List<SegmentPause> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val voiceNotes: List<VoiceNote> = emptyList(),
    val textNotes: List<TextNote> = emptyList(),
    /** Hourly weather along the segment (backlog D3). */
    val weather: List<WeatherSample> = emptyList(),
)

/** A data source whose license requires a credit in the exported diary (DISENO §11.5). */
data class Attribution(val name: String, val license: String, val url: String)

/**
 * The export input (DISENO §12): the whole trip as domain objects plus the relative
 * paths the media and audio files will have inside the ZIP. Pure data, so every writer
 * is testable on the JVM.
 */
data class TripExport(
    val trip: Trip,
    val segments: List<SegmentExport>,
    val appVersion: String,
    val exportedUtcMs: Long,
    /** media id → path inside the ZIP (`media/fotos/2026-10-02/IMG_1.jpg`); absent = not included. */
    val mediaFiles: Map<String, String> = emptyMap(),
    /** voice note id → path inside the ZIP (`audio/2026-10-02/nota_101732.m4a`); absent = not included. */
    val audioFiles: Map<String, String> = emptyMap(),
    /** Places visited (DISENO §11), their texts and which segment saw them. */
    val places: List<Place> = emptyList(),
    val enrichments: List<Enrichment> = emptyList(),
    val segmentPlaces: List<SegmentPlace> = emptyList(),
    /** Credits required by the sources used (OSM always; Wikipedia and Wikidata when a text came from them). */
    val attributions: List<Attribution> = attributionsFor(enrichments, segments.any { it.weather.isNotEmpty() }),
    val includeFilteredPoints: Boolean = false,
) {
    /** Places of one segment, best ranked first. */
    fun placesOf(segmentId: String): List<Pair<Place, SegmentPlace>> = segmentPlaces.filter { it.segmentId == segmentId }
        .sortedByDescending { it.relevance ?: 0.0 }.mapNotNull { sp -> places.firstOrNull { it.id == sp.placeId }?.let { it to sp } }

    fun enrichmentsOf(placeId: String): List<Enrichment> = enrichments.filter { it.placeId == placeId }

    val allMedia: List<MediaItem> get() = segments.flatMap { it.media }
    val allVoiceNotes: List<VoiceNote> get() = segments.flatMap { it.voiceNotes }
    val allTextNotes: List<TextNote> get() = segments.flatMap { it.textNotes }
    val allStops: List<Stop> get() = segments.flatMap { it.stops }
    val allWeather: List<WeatherSample> get() = segments.flatMap { it.weather }
    val startUtcMs: Long get() = segments.minOfOrNull { it.segment.startedUtcMs } ?: trip.startedUtcMs ?: trip.createdUtcMs
    val endUtcMs: Long? get() = segments.mapNotNull { it.segment.endedUtcMs }.maxOrNull() ?: trip.endedUtcMs
    val tzId: String get() = segments.firstOrNull()?.segment?.tzId ?: "UTC"

    companion object {
        val DEFAULT_ATTRIBUTIONS = listOf(
            Attribution("Datos del mapa © colaboradores de OpenStreetMap", "ODbL", "https://www.openstreetmap.org/copyright"),
        )

        val WEATHER_ATTRIBUTION = Attribution("Datos meteorológicos: Open-Meteo", WeatherSample.LICENSE_CC_BY, WeatherSample.SOURCE_URL)

        /** DISENO §11.5: every Wikipedia article used is cited with its URL (CC BY-SA 4.0); Wikidata once (CC0); Open-Meteo once when any hour has weather (D3). */
        fun attributionsFor(enrichments: List<Enrichment>, hasWeather: Boolean = false): List<Attribution> = buildList {
            addAll(DEFAULT_ATTRIBUTIONS)
            enrichments.filter { it.sourceName == Enrichment.SOURCE_WIKIPEDIA }.distinctBy { it.sourceUrl }.forEach {
                add(Attribution("Texto: Wikipedia — ${it.title ?: it.sourceUrl}", it.license ?: Enrichment.LICENSE_CC_BY_SA, it.sourceUrl))
            }
            if (enrichments.any { it.sourceName == Enrichment.SOURCE_WIKIDATA }) add(Attribution("Datos: Wikidata", Enrichment.LICENSE_CC0, "https://www.wikidata.org"))
            if (hasWeather) add(WEATHER_ATTRIBUTION)
        }
    }
}

/** One writer per format: streams to [out]; [path] is where it lives inside the ZIP. */
interface TripWriter {
    val path: String

    fun write(trip: TripExport, out: java.io.OutputStream)
}
