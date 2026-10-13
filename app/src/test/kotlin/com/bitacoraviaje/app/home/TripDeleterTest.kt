package com.bitacoraviaje.app.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.app.export.BackupFiles
import com.bitacoraviaje.app.export.ExportLoader
import com.bitacoraviaje.app.video.VideoPlanStore
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.PlacesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Backlog D1 (review B1/I3/I5): a trip is deleted only after its backup exists; recordings are kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripDeleterTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var tracking: TrackingRepository
    private lateinit var notes: NotesRepository
    private lateinit var deleter: TripDeleter
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val t0 = 1_760_000_000_000L
    private var now = t0

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        tracking = TrackingRepository(db, Clock { now })
        notes = NotesRepository(db)
        deleter = TripDeleter(context, tracking, ExportLoader(tracking, MediaRepository(db), notes, PlacesRepository(db)), VideoPlanStore(context))
        BackupFiles.dir(context).deleteRecursively()
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun tripWithVoiceNote(finish: Boolean): String {
        val s = tracking.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "Borrar")
        tracking.insertPoints((0 until 5).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 1000L, lat = -34.6 + i * 1e-4, lon = -58.4) })
        val audio = File(TripDeleter.audioDir(context, s.tripId), "v1.m4a").also { it.parentFile?.mkdirs(); it.writeBytes(ByteArray(16) { 7 }) }
        notes.addVoiceNote(VoiceNote("v1", s.id, t0 + 2_000, 3_000, -34.6, -58.4, 5f, "audio/${s.tripId}/v1.m4a"))
        assertTrue(audio.isFile)
        now = t0 + 10_000
        if (finish) tracking.finishTrip(s.tripId)
        return s.tripId
    }

    @Test
    fun `a recording trip is refused and nothing is touched`() = runTest {
        val id = tripWithVoiceNote(finish = false)
        assertEquals(TripDeleter.Outcome.IN_USE, deleter.delete(id))
        assertNotNull(tracking.getTrip(id))
        assertTrue(File(TripDeleter.audioDir(context, id), "v1.m4a").isFile)
        assertFalse(BackupFiles.fileFor(context, id).exists(), "no backup is written for a refused delete")
    }

    @Test
    fun `when the backup fails or is empty the trip and its recordings stay`() = runTest {
        val id = tripWithVoiceNote(finish = true)
        assertEquals(TripDeleter.Outcome.BACKUP_FAILED, deleter.delete(id) { null })
        assertEquals(TripDeleter.Outcome.BACKUP_FAILED, deleter.delete(id) { throw java.io.IOException("disk full") })
        assertEquals(TripDeleter.Outcome.BACKUP_FAILED, deleter.delete(id) { File(context.cacheDir, "empty.gz").also { it.writeBytes(ByteArray(0)) } })
        assertNotNull(tracking.getTrip(id))
        assertTrue(File(TripDeleter.audioDir(context, id), "v1.m4a").isFile)
        assertFalse(TripDeleter.audioTrashDir(context, id).exists())
    }

    @Test
    fun `with the backup written the rows go and the recordings move next to the backup`() = runTest {
        val id = tripWithVoiceNote(finish = true)
        assertEquals(TripDeleter.Outcome.DELETED, deleter.delete(id))
        assertNull(tracking.getTrip(id))
        assertTrue(tracking.observeTrips().first().isEmpty())
        val backup = BackupFiles.fileFor(context, id)
        assertTrue(backup.isFile && backup.length() > 0, "the real backup exists")
        assertFalse(TripDeleter.audioDir(context, id).exists())
        val kept = File(TripDeleter.audioTrashDir(context, id), "v1.m4a")
        assertTrue(kept.isFile, "the recording is kept, not deleted")
        assertEquals(16, kept.length().toInt())
        assertEquals(TripDeleter.Outcome.NOT_FOUND, deleter.delete(id), "a second run is harmless")
    }
}
