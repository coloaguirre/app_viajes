package com.bitacoraviaje.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.data.db.entity.SegmentPauseEntity
import com.bitacoraviaje.data.db.entity.StopEntity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaoTest {
    private lateinit var db: BitacoraDatabase

    @BeforeTest
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BitacoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `trip roundtrip and active lookup`() = runTest {
        db.tripDao().insert(Fixtures.trip("t1", status = "FINISHED"))
        db.tripDao().insert(Fixtures.trip("t2", status = "ACTIVE"))
        assertEquals("t2", db.tripDao().getActive()?.id)
        assertEquals(listOf("t2", "t1").toSet(), db.tripDao().observeAll().first().map { it.id }.toSet())
        assertEquals("Trip t1", db.tripDao().getById("t1")?.title)
    }

    @Test
    fun `segment requires an existing trip (foreign key)`() = runTest {
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            db.segmentDao().insert(Fixtures.segment("s1", tripId = "missing"))
        }
    }

    @Test
    fun `open segment is the active or paused one, newest first`() = runTest {
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment("old", status = "FINISHED", startedUtcMs = Fixtures.T0))
        db.segmentDao().insert(Fixtures.segment("cur", status = "PAUSED", startedUtcMs = Fixtures.T0 + 1))
        assertEquals("cur", db.segmentDao().getOpen()?.id)
        assertEquals("cur", db.segmentDao().observeOpen().first()?.id)
    }

    @Test
    fun `nested segment references its parent`() = runTest {
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment("day"))
        db.segmentDao().insert(Fixtures.segment("hike", parent = "day", startedUtcMs = Fixtures.T0 + 10))
        assertEquals("day", db.segmentDao().getById("hike")?.parentSegmentId)
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            db.segmentDao().insert(Fixtures.segment("orphan", parent = "nope"))
        }
    }

    @Test
    fun `pause open and resume`() = runTest {
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
        db.segmentDao().insertPause(SegmentPauseEntity("p1", "s1", "AUTO_REST", Fixtures.T0 + 100, null))
        val open = assertNotNull(db.segmentDao().getOpenPause("s1"))
        db.segmentDao().updatePause(open.copy(resumedUtcMs = Fixtures.T0 + 200))
        assertNull(db.segmentDao().getOpenPause("s1"))
        assertEquals(1, db.segmentDao().getPauses("s1").size)
    }

    @Test
    fun `track points batch insert, valid filter, last valid and count`() = runTest {
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
        val pts = (0 until 10).map { Fixtures.point(i = it, filtered = it % 3 == 0) }
        db.trackPointDao().insertAll(pts)
        assertEquals(10, db.trackPointDao().countBySegment("s1"))
        val valid = db.trackPointDao().getValidBySegment("s1")
        assertEquals(6, valid.size)
        assertEquals(Fixtures.T0 + 8 * 5_000L, db.trackPointDao().getLastValid("s1")?.timeUtcMs)
        assertEquals(valid.map { it.timeUtcMs }, valid.map { it.timeUtcMs }.sorted())
    }

    @Test
    fun `markSimplified keeps only the given ids`() = runTest {
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
        db.trackPointDao().insertAll((0 until 5).map { Fixtures.point(i = it) })
        val ids = db.trackPointDao().getBySegment("s1").map { it.id }
        db.trackPointDao().markSimplified("s1", listOf(ids.first(), ids.last()))
        val kept = db.trackPointDao().getBySegment("s1").filter { it.isSimplifiedKeep }.map { it.id }
        assertEquals(listOf(ids.first(), ids.last()), kept)
    }

    @Test
    fun `stops are replaced atomically per segment`() = runTest {
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
        fun stop(id: String) = StopEntity(id, "s1", Fixtures.T0, Fixtures.T0 + 300_000, -34.6, -58.4, 50.0, null, null)
        db.stopDao().replaceForSegment("s1", listOf(stop("a"), stop("b")))
        db.stopDao().replaceForSegment("s1", listOf(stop("c")))
        assertEquals(listOf("c"), db.stopDao().getBySegment("s1").map { it.id })
    }

    @Test
    fun `deleting a trip cascades to everything below it`() = runTest {
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
        db.trackPointDao().insertAll((0 until 3).map { Fixtures.point(i = it) })
        db.stopDao().insertAll(listOf(StopEntity("a", "s1", Fixtures.T0, Fixtures.T0 + 1, 0.0, 0.0, 1.0, null, null)))
        db.tripDao().delete("t1")
        assertNull(db.segmentDao().getById("s1"))
        assertEquals(0, db.trackPointDao().countBySegment("s1"))
        assertEquals(0, db.stopDao().getBySegment("s1").size)
    }
}
