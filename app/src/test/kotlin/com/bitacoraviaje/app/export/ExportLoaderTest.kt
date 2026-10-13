package com.bitacoraviaje.app.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportLoaderTest {
    private lateinit var db: BitacoraDatabase
    private val t0 = 1_760_000_000_000L
    private var now = t0

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BitacoraDatabase::class.java).allowMainThreadQueries().build()
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `loads every entity of the trip and assigns unique zip paths`() = runTest {
        val tracking = TrackingRepository(db, Clock { now })
        val media = MediaRepository(db)
        val notes = NotesRepository(db)
        val s = tracking.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", tripTitle = "Viaje de prueba")
        tracking.insertPoints((0 until 20).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 10_000L, lat = -34.6 + i * 1e-4, lon = -58.4, isFilteredOut = i == 5) })
        media.upsertAll(
            listOf(
                MediaItem("m1", s.tripId, s.id, MediaKind.PHOTO, MediaSource.MEDIASTORE, "content://media/1", 1, "IMG_1.jpg", "image/jpeg", 1, 1, 1, null, 0, t0 + 50_000, TakenTimeSource.EXIF_WITH_OFFSET, null, null, null, null, -34.6, -58.4, LinkMethod.TRACK_INTERPOLATED, 0.8),
                MediaItem("m2", s.tripId, s.id, MediaKind.PHOTO, MediaSource.MEDIASTORE, "content://media/2", 2, "IMG_1.jpg", "image/jpeg", 1, 1, 1, null, 0, t0 + 60_000, TakenTimeSource.EXIF_WITH_OFFSET, null, null, null, null, -34.6, -58.4, LinkMethod.TRACK_INTERPOLATED, 0.8),
                MediaItem("m3", s.tripId, null, MediaKind.VIDEO, MediaSource.MEDIASTORE, "content://media/3", 3, "VID_1.mp4", "video/mp4", 1, 1, 1, 5_000, 0, t0 + 70_000, TakenTimeSource.MEDIASTORE),
                MediaItem("m4", s.tripId, s.id, MediaKind.PHOTO, MediaSource.MEDIASTORE, "content://media/4", 4, "x.jpg", "image/jpeg", 1, 1, 1, null, 0, t0 + 80_000, TakenTimeSource.MEDIASTORE, isExcluded = true),
            ),
        )
        notes.addVoiceNote(VoiceNote("v1", s.id, t0 + 90_000, 3_000, -34.6, -58.4, 5f, "audio/t/v1.m4a"))
        notes.addTextNote(s.id, TextNoteKind.HIGHLIGHT, t0 + 100_000, -34.6, -58.4)
        now = t0 + 200_000
        tracking.finishSegment(s.id)

        val loader = ExportLoader(tracking, media, notes)
        val export = assertNotNull(loader.load(s.tripId, ExportOptions(), nowUtcMs = now))
        assertEquals(1, export.segments.size)
        val se = export.segments.single()
        assertEquals(19, se.points.size, "filtered point left out by default")
        assertEquals(4, se.media.size, "excluded media travel too (flagged, no file); the unlinked video rides with the first segment")
        assertTrue(se.media.single { it.id == "m4" }.isExcluded && "m4" !in export.mediaFiles, "excluded item keeps its flag and gets no file")
        assertEquals(1, se.voiceNotes.size)
        assertEquals(1, se.textNotes.size)
        assertEquals(3, export.mediaFiles.size)
        assertEquals(3, export.mediaFiles.values.toSet().size, "two photos named IMG_1.jpg get distinct paths")
        assertTrue(export.mediaFiles.values.all { it.startsWith("media/fotos/") || it.startsWith("media/videos/") })
        assertTrue(export.audioFiles.getValue("v1").startsWith("audio/2025-10-09/nota_"))

        val debug = assertNotNull(loader.load(s.tripId, ExportOptions(includeMedia = false, includeFilteredPoints = true), nowUtcMs = now))
        assertEquals(20, debug.segments.single().points.size)
        assertTrue(debug.mediaFiles.isEmpty())
    }
}
