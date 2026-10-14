package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.PlaceSource
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.SegmentStats
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.TripStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.model.WeatherSample
import java.io.ByteArrayOutputStream

/** Synthetic two-segment trip with every entity kind and awkward characters in the texts. */
object Fixture {
    const val TZ = "America/Argentina/Buenos_Aires"
    const val T0 = 1_760_000_000_000L // 2025-10-09T08:53:20Z
    const val TRICKY = "Café \"La Ñata\" & <amigos> · 'noche' 🙂 </script><script>alert(1)</script>"

    fun walk(segmentId: String, start: Long, n: Int, stepS: Int = 10, filteredEvery: Int = 0): List<TrackPoint> = (0 until n).map { i ->
        TrackPoint(
            segmentId = segmentId, timeUtcMs = start + i * stepS * 1000L, lat = -34.6 + i * 10 / 111_320.0, lon = -58.4 + (i % 7) / 1_000_000.0,
            altM = 20.0 + (i % 10), hAccuracyM = 5f, speedMps = 1.1f, bearingDeg = 0f, provider = "fused",
            isFilteredOut = filteredEvery > 0 && i % filteredEvery == 0 && i > 0,
        )
    }

    fun trip(): TripExport {
        val trip = Trip(id = "trip-1", title = "Patagonia 2026 $TRICKY", description = "Prueba & demo", createdUtcMs = T0, startedUtcMs = T0, endedUtcMs = T0 + 86_400_000 + 7_200_000, status = TripStatus.FINISHED)
        val s1 = Segment("seg-1", "trip-1", null, SegmentType.TRIP_START, Transport.WALK, "Caminata inicial", null, T0, T0 + 3_600_000, TZ, "WALK", RecordingMode.FULL, false, SegmentStatus.FINISHED, SegmentStats(distanceM = 3_500.0, movingTimeMs = 3_000_000, totalTimeMs = 3_600_000, maxSpeedMps = 1.8, elevationGainM = 40.0))
        val p1 = walk("seg-1", T0, 361, filteredEvery = 50)
        val pauses1 = listOf(SegmentPause("pa-1", "seg-1", PauseKind.USER, T0 + 1_200_000, T0 + 1_500_000), SegmentPause("pa-2", "seg-1", PauseKind.AUTO_REST, T0 + 2_400_000, T0 + 2_700_000))
        val stops1 = listOf(Stop("st-1", "seg-1", T0 + 1_200_000, T0 + 1_500_000, -34.59, -58.4, 25.0, null, "Mirador"))
        val media1 = listOf(
            MediaItem("m-1", "trip-1", "seg-1", MediaKind.PHOTO, MediaSource.MEDIASTORE, "content://media/1", 1, "IMG_0001.jpg", "image/jpeg", 1000, 4000, 3000, null, 0, T0 + 600_000, TakenTimeSource.EXIF_WITH_OFFSET, -34.595, -58.4, "Google", "Pixel 8 Pro", -34.595, -58.4, LinkMethod.EXIF, 1.0, 0, false, null, TRICKY, true, false),
            MediaItem("m-2", "trip-1", "seg-1", MediaKind.VIDEO, MediaSource.MEDIASTORE, "content://media/2", 2, "VID_0002.mp4", "video/mp4", 5000, 1920, 1080, 8_000, 0, T0 + 1_800_000, TakenTimeSource.MEDIASTORE, null, null, null, null, -34.585, -58.4, LinkMethod.TRACK_INTERPOLATED, 0.8, 0, false),
            MediaItem("m-3", "trip-1", "seg-1", MediaKind.PHOTO, MediaSource.MEDIASTORE, "content://media/3", 3, "IMG_0003.jpg", "image/jpeg", 1000, 4000, 3000, null, 0, T0 + 3_000_000, TakenTimeSource.EXIF_LOCAL, null, null, null, null, null, null, LinkMethod.NONE, 0.0, null, false),
        )
        val voice1 = listOf(
            VoiceNote("v-1", "seg-1", T0 + 900_000, 7_500, -34.592, -58.4, 5f, "audio/trip-1/v-1.m4a", transcript = "Llegamos al mirador, el viento es tremendo", transcriptStatus = TranscriptStatus.DONE, transcriptLang = "es-US", transcriptEngine = "android-speech"),
            VoiceNote("v-2", "seg-1", T0 + 2_000_000, 4_000, -34.582, -58.4, 5f, "audio/trip-1/v-2.m4a", transcriptStatus = TranscriptStatus.FAILED),
        )
        val text1 = listOf(
            TextNote("n-1", "seg-1", T0 + 1_000_000, -34.591, -58.4, TextNoteKind.MARKER, TRICKY),
            TextNote("n-2", "seg-1", T0 + 2_100_000, -34.581, -58.4, TextNoteKind.HIGHLIGHT),
            TextNote("n-3", "seg-1", T0 + 2_200_000, -34.58, -58.4, TextNoteKind.TRANSPORT_CHANGE, "BIKE"),
        )
        // Backlog D3: two sampled hours of seg-1 (08:00 and 09:00 UTC = 05:00 / 06:00 local): clear then cloudy.
        val weather1 = listOf(
            WeatherSample("w-1", "seg-1", T0 - T0 % 3_600_000 + 1_800_000, -34.6, -58.4, 14.2, 12.0, 0.0, 12.0, 10, 0),
            WeatherSample("w-2", "seg-1", T0 - T0 % 3_600_000 + 5_400_000, -34.59, -58.4, 16.8, 15.0, 0.4, 25.0, 60, 2),
        )
        val s2start = T0 + 86_400_000
        val s2 = Segment("seg-2", "trip-1", null, SegmentType.STAGE, Transport.CAR, null, null, s2start, s2start + 7_200_000, TZ, "VEHICLE", RecordingMode.ECONOMY, false, SegmentStatus.FINISHED, SegmentStats(distanceM = 51_000.0, movingTimeMs = 6_000_000, totalTimeMs = 7_200_000))
        val p2 = walk("seg-2", s2start, 120, stepS = 60)
        return TripExport(
            trip = trip,
            segments = listOf(SegmentExport(s1, p1, pauses1, stops1, media1, voice1, text1, weather1), SegmentExport(s2, p2)),
            appVersion = "0.1.0-test",
            exportedUtcMs = T0 + 3 * 86_400_000,
            mediaFiles = mapOf("m-1" to "media/fotos/2025-10-09/IMG_0001.jpg", "m-2" to "media/videos/2025-10-09/VID_0002.mp4", "m-3" to "media/fotos/2025-10-09/IMG_0003.jpg"),
            audioFiles = mapOf("v-1" to "audio/2025-10-09/nota_061500_v-1.m4a", "v-2" to "audio/2025-10-09/nota_063320_v-2.m4a"),
            places = listOf(
                Place("wd-Q1", "Mirador $TRICKY", -34.59, -58.4, kind = "viewpoint", countryCode = "ar", admin1 = "Buenos Aires", locality = "Palermo", wikidataId = "Q1", wikipediaTitle = "Mirador", wikipediaLang = "es", source = PlaceSource.WIKIPEDIA, fetchedUtcMs = T0),
                Place("osm-N5", "Cerro sin texto", -34.58, -58.41, kind = "peak", source = PlaceSource.OVERPASS, osmType = "node", osmId = 5, fetchedUtcMs = T0),
            ),
            enrichments = listOf(
                Enrichment("wd-Q1-wikipedia-es", "wd-Q1", "es", "Mirador", "Un mirador $TRICKY con vista al río.", Enrichment.SOURCE_WIKIPEDIA, "https://es.wikipedia.org/wiki/Mirador", Enrichment.LICENSE_CC_BY_SA, "https://thumb.example/x.jpg", fetchedUtcMs = T0),
                Enrichment("wd-Q1-wikidata", "wd-Q1", "es", "Mirador", "Altitud: 25 m", Enrichment.SOURCE_WIKIDATA, "https://www.wikidata.org/wiki/Q1", Enrichment.LICENSE_CC0, fetchedUtcMs = T0, userApproved = true),
            ),
            segmentPlaces = listOf(SegmentPlace("seg-1", "wd-Q1", T0 + 1_200_000, 300_000, 3.2), SegmentPlace("seg-1", "osm-N5", T0 + 600_000, null, 0.4)),
        )
    }

    fun render(writer: TripWriter, trip: TripExport = trip()): String {
        val out = ByteArrayOutputStream()
        writer.write(trip, out)
        return out.toString(Charsets.UTF_8)
    }
}
