package com.bitacoraviaje.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.TripStatus
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.TrackingRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackingRepositoryTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var repo: TrackingRepository
    private var now = Fixtures.T0
    private val clock = Clock { now }

    @BeforeTest
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        repo = TrackingRepository(db, clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `first session creates an active trip and an active segment with its profile`() = runTest {
        val s = repo.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", tripTitle = "Patagonia")
        val trip = assertNotNull(repo.observeActiveTrip().first())
        assertEquals("Patagonia", trip.title)
        assertEquals(TripStatus.ACTIVE, trip.status)
        assertEquals(trip.id, s.tripId)
        assertEquals("WALK", s.samplingProfile)
        assertEquals(s.id, repo.getOpenSegment()?.id)
    }

    @Test
    fun `changing transport mid-segment updates profile and records a timeline note`() = runTest {
        val s = repo.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 60_000
        val u = assertNotNull(repo.changeTransport(s.id, Transport.CAR, -34.6, -58.4))
        assertEquals(Transport.CAR, u.transport)
        assertEquals("VEHICLE", u.samplingProfile)
        assertEquals(Transport.CAR, repo.getSegment(s.id)?.transport)
        val note = db.textNoteDao().observeBySegment(s.id).first().single()
        assertEquals(TextNoteKind.TRANSPORT_CHANGE.name, note.kind)
        assertEquals("CAR", note.text)
        assertEquals(now, note.timeUtcMs)
        repo.finishSegment(s.id)
        assertEquals(null, repo.changeTransport(s.id, Transport.BIKE), "closed segments are left alone")
    }

    @Test
    fun `economy mode picks the economy profile`() = runTest {
        val s = repo.startSession(SegmentType.TREKKING, Transport.WALK, RecordingMode.ECONOMY, "UTC", tripTitle = "T")
        assertEquals("ECONOMY", s.samplingProfile)
    }

    @Test
    fun `a new stage finishes the previous open one in the same trip`() = runTest {
        val a = repo.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 60_000
        val b = repo.startSession(SegmentType.STAGE, Transport.BUS, RecordingMode.FULL, "UTC", tripTitle = "T")
        assertEquals(a.tripId, b.tripId)
        assertEquals(SegmentStatus.FINISHED, repo.getSegment(a.id)?.status)
        assertEquals(b.id, repo.getOpenSegment()?.id)
    }

    @Test
    fun `an excursion nests under the open stage and pauses it`() = runTest {
        val day = repo.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 60_000
        val hike = repo.startSession(SegmentType.EXCURSION, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        assertEquals(day.id, hike.parentSegmentId)
        assertEquals(SegmentStatus.PAUSED, repo.getSegment(day.id)?.status)
        val pauses = repo.pausesOf(day.id)
        assertEquals(1, pauses.size)
        assertEquals(PauseKind.NESTED, pauses[0].kind)
        assertNull(pauses[0].resumedUtcMs)
    }

    @Test
    fun `pause and resume bracket a pause row`() = runTest {
        val s = repo.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 10_000
        repo.pause(s.id, PauseKind.USER)
        assertEquals(SegmentStatus.PAUSED, repo.getSegment(s.id)?.status)
        now += 20_000
        repo.resume(s.id)
        assertEquals(SegmentStatus.ACTIVE, repo.getSegment(s.id)?.status)
        val p = repo.pausesOf(s.id).single()
        assertEquals(Fixtures.T0 + 10_000, p.pausedUtcMs)
        assertEquals(Fixtures.T0 + 30_000, p.resumedUtcMs)
    }

    @Test
    fun `finishing computes stats, stops and simplification from accepted points`() = runTest {
        val s = repo.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        // 10 min walk north at 1.1 m/s, then 6 min still, then 2 rejected points.
        val walk = (0 until 120).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = Fixtures.T0 + i * 5_000L, lat = -34.6 + i * 5.5 / 111_320, lon = -58.4, altM = 10.0) }
        val still = (0 until 72).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = walk.last().timeUtcMs + (i + 1) * 5_000L, lat = walk.last().lat, lon = -58.4, altM = 10.0) }
        val junk = (0 until 2).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = still.last().timeUtcMs + (i + 1) * 5_000L, lat = 0.0, lon = 0.0, isFilteredOut = true) }
        repo.insertPoints(walk + still + junk)
        now = junk.last().timeUtcMs
        val done = assertNotNull(repo.finishSegment(s.id))
        assertEquals(SegmentStatus.FINISHED, done.status)
        assertEquals(119 * 5.5, done.stats.distanceM!!, 2.0)
        assertEquals(119 * 5_000L, done.stats.movingTimeMs)
        assertNotNull(done.stats.bbox)
        assertEquals(1, repo.stopsOf(s.id).size)
        val valid = repo.validPoints(s.id)
        assertEquals(192, valid.size)
        assertTrue(valid.count { it.isSimplifiedKeep } < 20, "straight line simplifies to a handful of points")
        assertNull(repo.getOpenSegment())
    }

    @Test
    fun `cutDay closes the daily segment and opens an auto-created one in the same trip`() = runTest {
        val day1 = repo.startSession(SegmentType.TRIP_START, Transport.BUS, RecordingMode.ECONOMY, "America/Argentina/Buenos_Aires", tripTitle = "T")
        now += 3_600_000
        val day2 = assertNotNull(repo.cutDay(day1.id))
        assertEquals(SegmentStatus.FINISHED, repo.getSegment(day1.id)?.status)
        assertEquals(day1.tripId, day2.tripId)
        assertEquals(SegmentType.STAGE, day2.type)
        assertEquals(Transport.BUS, day2.transport)
        assertEquals(RecordingMode.ECONOMY, day2.recordingMode)
        assertEquals("ECONOMY", day2.samplingProfile)
        assertTrue(day2.autoCreated)
        assertEquals(day2.id, repo.getOpenSegment()?.id)
    }

    @Test
    fun `cutDay carries an automatic rest into the new day and never cuts a nested excursion`() = runTest {
        val day = repo.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        repo.pause(day.id, PauseKind.AUTO_REST)
        now += 1_000
        val next = assertNotNull(repo.cutDay(day.id))
        assertEquals(SegmentStatus.PAUSED, repo.getSegment(next.id)?.status)
        assertEquals(PauseKind.AUTO_REST, repo.pausesOf(next.id).single().kind)
        now += 1_000
        repo.resume(next.id)
        val hike = repo.startSession(SegmentType.EXCURSION, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        assertNull(repo.cutDay(hike.id), "nested segments are not cut at midnight")
        assertEquals(SegmentStatus.ACTIVE, repo.getSegment(hike.id)?.status)
    }

    @Test
    fun `finishing the trip from a nested excursion also closes the paused parent`() = runTest {
        val day = repo.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 1_000
        val hike = repo.startSession(SegmentType.EXCURSION, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 1_000
        repo.finishTrip(day.tripId)
        assertEquals(SegmentStatus.FINISHED, repo.getSegment(hike.id)?.status)
        assertEquals(SegmentStatus.FINISHED, repo.getSegment(day.id)?.status)
        assertNull(repo.getOpenSegment(), "no orphan left behind")
        assertNotNull(repo.pausesOf(day.id).single().resumedUtcMs, "the NESTED pause is closed")
    }

    @Test
    fun `an excursion inside an excursion closes the inner one and hangs from the stage`() = runTest {
        val day = repo.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 1_000
        val a = repo.startSession(SegmentType.EXCURSION, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 1_000
        val b = repo.startSession(SegmentType.TREKKING, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        assertEquals(SegmentStatus.FINISHED, repo.getSegment(a.id)?.status)
        assertEquals(day.id, b.parentSegmentId)
        assertEquals(SegmentStatus.PAUSED, repo.getSegment(day.id)?.status)
    }

    @Test
    fun `cutDay stores the device time zone of the new day`() = runTest {
        val day = repo.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", tripTitle = "T")
        now += 1_000
        val next = assertNotNull(repo.cutDay(day.id, currentTzId = "Europe/Madrid"))
        assertEquals("Europe/Madrid", next.tzId)
    }

    @Test
    fun `finishing the trip closes the open segment and the trip`() = runTest {
        val s = repo.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        now += 5_000
        repo.finishTrip(s.tripId)
        assertEquals(SegmentStatus.FINISHED, repo.getSegment(s.id)?.status)
        assertNull(repo.observeActiveTrip().first())
        assertEquals(TripStatus.FINISHED, repo.observeTrips().first().single().status)
    }
}
