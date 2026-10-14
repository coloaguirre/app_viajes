package com.bitacoraviaje.app.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.export.TripExport
import com.bitacoraviaje.core.export.TripJsonReader
import com.bitacoraviaje.core.export.TripJsonWriter
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import java.io.ByteArrayOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 3.3 acceptance: exporting and importing a trip gives the same database content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportRoundTripTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var tracking: TrackingRepository
    private lateinit var media: MediaRepository
    private lateinit var notes: NotesRepository
    private val t0 = 1_760_000_000_000L
    private var now = t0

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BitacoraDatabase::class.java).allowMainThreadQueries().build()
        tracking = TrackingRepository(db, Clock { now })
        media = MediaRepository(db)
        notes = NotesRepository(db)
    }

    @AfterTest
    fun tearDown() = db.close()

    /** Everything the database holds for a trip, independent of ids and content URIs. */
    private fun fingerprint(t: TripExport): String = buildString {
        appendLine("trip ${t.trip.title}|${t.trip.status}|${t.trip.startedUtcMs}|${t.trip.endedUtcMs}")
        for (se in t.segments) {
            val s = se.segment
            appendLine("seg ${s.type}|${s.transport}|${s.title}|${s.startedUtcMs}|${s.endedUtcMs}|${s.tzId}|${s.samplingProfile}|${s.recordingMode}|${s.status}|${s.stats.distanceM}|${s.stats.movingTimeMs}")
            appendLine("pts " + se.points.joinToString(",") { "${it.timeUtcMs}:${it.lat}:${it.lon}:${it.altM}:${it.isFilteredOut}" })
            appendLine("pauses " + se.pauses.joinToString(",") { "${it.kind}:${it.pausedUtcMs}:${it.resumedUtcMs}" })
            appendLine("stops " + se.stops.joinToString(",") { "${it.startUtcMs}:${it.endUtcMs}:${it.radiusM}" })
            appendLine("media " + se.media.joinToString(",") { "${it.kind}:${it.displayName}:${it.takenUtcMs}:${it.linkedLat}:${it.linkedLon}:${it.linkMethod}:${it.linkConfidence}:${it.userCaption}:${it.isFavorite}" })
            appendLine("voice " + se.voiceNotes.joinToString(",") { "${it.startedUtcMs}:${it.durationMs}:${it.lat}:${it.lon}:${it.displayText}:${it.transcriptStatus}" })
            appendLine("text " + se.textNotes.joinToString(",") { "${it.kind}:${it.timeUtcMs}:${it.lat}:${it.lon}:${it.text}" })
            appendLine("places " + t.placesOf(se.segment.id).joinToString(",") { (p, sp) -> "${p.id}:${p.name}:${p.kind}:${p.source}:${sp.firstSeenUtcMs}:${sp.dwellMs}:${sp.relevance}" })
            appendLine("weather " + se.weather.joinToString(",") { "${it.timeUtcMs}:${it.lat}:${it.lon}:${it.temperatureC}:${it.weatherCode}:${it.source}" })
        }
        appendLine("texts " + t.enrichments.joinToString(",") { "${it.id}:${it.summary}:${it.sourceName}:${it.license}:${it.userApproved}" })
        appendLine("credits " + t.attributions.joinToString(",") { "${it.name}:${it.license}" })
    }

    @Test
    fun `export then import as a copy yields the same database content`() = runTest {
        val s = tracking.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", title = "Caminata", tripTitle = "Ida y vuelta")
        tracking.insertPoints((0 until 60).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 10_000L, lat = -34.6 + i * 1e-4, lon = -58.4, altM = 20.0 + i % 5, isFilteredOut = i == 7) })
        now = t0 + 200_000
        tracking.pause(s.id, PauseKind.USER)
        now = t0 + 260_000
        tracking.resume(s.id)
        media.upsertAll(listOf(MediaItem("m1", s.tripId, s.id, MediaKind.PHOTO, MediaSource.MEDIASTORE, "content://media/1", 1, "IMG_1.jpg", "image/jpeg", 1, 1, 1, null, 0, t0 + 50_000, TakenTimeSource.EXIF_WITH_OFFSET, -34.6, -58.4, "Google", "Pixel", -34.6, -58.4, LinkMethod.EXIF, 1.0, 0, false, null, "Mirador", true)))
        notes.addVoiceNote(VoiceNote("v1", s.id, t0 + 90_000, 3_000, -34.6, -58.4, 5f, "audio/t/v1.m4a", transcript = "hola", transcriptStatus = com.bitacoraviaje.core.model.TranscriptStatus.DONE))
        notes.addTextNote(s.id, TextNoteKind.MARKER, t0 + 100_000, -34.6, -58.4, "cumbre")
        now = t0 + 600_000
        tracking.finishSegment(s.id)
        tracking.finishTrip(s.tripId)
        val placesRepo = com.bitacoraviaje.data.repository.PlacesRepository(db)
        placesRepo.replaceSegmentPlaces(
            s.id,
            listOf(com.bitacoraviaje.core.model.Place("wd-Q1", "Mirador", -34.6, -58.4, kind = "viewpoint", wikidataId = "Q1", source = com.bitacoraviaje.core.model.PlaceSource.WIKIPEDIA, fetchedUtcMs = now)),
            listOf(com.bitacoraviaje.core.model.Enrichment("wd-Q1-wikipedia-es", "wd-Q1", "es", "Mirador", "Un mirador.", "Wikipedia", "https://es.wikipedia.org/wiki/Mirador", "CC BY-SA 4.0", fetchedUtcMs = now, userApproved = true)),
            listOf(com.bitacoraviaje.core.model.SegmentPlace(s.id, "wd-Q1", t0 + 100_000, 60_000, 2.5)),
        )

        val weatherRepo = com.bitacoraviaje.data.repository.WeatherRepository(db)
        weatherRepo.replace(s.id, listOf(com.bitacoraviaje.core.model.WeatherSample("wx-1", s.id, t0 + 1_800_000, -34.6, -58.4, 21.5, 20.0, 0.0, 8.0, 30, 1, fetchedUtcMs = now)))
        val loader = ExportLoader(tracking, media, notes, placesRepo, weatherRepo)
        val original = assertNotNull(loader.load(s.tripId, ExportOptions(), nowUtcMs = now))
        val json = ByteArrayOutputStream().also { TripJsonWriter().write(original, it) }.toString(Charsets.UTF_8)

        val copy = TripJsonReader.read(json, regenerateIds = true)
        assertNotEquals(original.trip.id, copy.trip.id)
        tracking.importTrip(copy.trip, copy.segments.map { it.segment }, copy.segments.associate { it.segment.id to it.points }, copy.segments.flatMap { it.pauses }, copy.segments.flatMap { it.stops }, placesToInsert = copy.places, enrichmentsToInsert = copy.enrichments, segmentPlacesToInsert = copy.segmentPlaces, weatherToInsert = copy.allWeather)
        media.upsertAll(copy.allMedia)
        notes.addVoiceNotes(copy.allVoiceNotes)
        notes.addTextNotes(copy.allTextNotes)

        val reloaded = assertNotNull(loader.load(copy.trip.id, ExportOptions(), nowUtcMs = now))
        assertEquals(fingerprint(original), fingerprint(reloaded))
        assertEquals(2, tracking.observeTrips().first().size, "original and copy coexist")
        assertNotNull(weatherRepo.fetchedAt(copy.segments.single().segment.id), "imported hours count as looked up (review N3)")

        // Replace: importing the same id again over the existing trip leaves exactly one copy with the same content.
        val same = TripJsonReader.read(json)
        tracking.importTrip(same.trip, same.segments.map { it.segment }, same.segments.associate { it.segment.id to it.points }, same.segments.flatMap { it.pauses }, same.segments.flatMap { it.stops }, replace = true, placesToInsert = same.places, enrichmentsToInsert = same.enrichments, segmentPlacesToInsert = same.segmentPlaces, weatherToInsert = same.allWeather)
        media.upsertAll(same.allMedia)
        notes.addVoiceNotes(same.allVoiceNotes)
        notes.addTextNotes(same.allTextNotes)
        val replaced = assertNotNull(loader.load(original.trip.id, ExportOptions(), nowUtcMs = now))
        assertEquals(fingerprint(original), fingerprint(replaced))
        assertEquals(2, tracking.observeTrips().first().size)
    }
}
