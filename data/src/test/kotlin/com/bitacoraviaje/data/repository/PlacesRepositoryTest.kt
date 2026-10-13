package com.bitacoraviaje.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.PlaceSource
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.db.Fixtures
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlacesRepositoryTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var repo: PlacesRepository

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BitacoraDatabase::class.java).allowMainThreadQueries().build()
        repo = PlacesRepository(db)
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
    }

    @AfterTest
    fun tearDown() = db.close()

    private val place = Place("wd-Q1", "Pueblo", -49.3, -72.9, kind = "town", source = PlaceSource.NOMINATIM, wikidataId = "Q1")
    private fun wiki(summary: String) = Enrichment("wd-Q1-wikipedia-es", "wd-Q1", "es", "Pueblo", summary, Enrichment.SOURCE_WIKIPEDIA, "https://es.wikipedia.org/wiki/Pueblo", Enrichment.LICENSE_CC_BY_SA, fetchedUtcMs = 1)

    @Test
    fun `re-running the enrichment keeps the user's approval and rewrites the links`() = runTest {
        repo.replaceSegmentPlaces("s1", listOf(place), listOf(wiki("v1")), listOf(SegmentPlace("s1", "wd-Q1", Fixtures.T0, 60_000, 2.0)))
        assertTrue(repo.hasPlaces("s1"))
        repo.setApproved("wd-Q1-wikipedia-es", true)
        val other = place.copy(id = "wd-Q2", name = "Otro", wikidataId = "Q2")
        repo.replaceSegmentPlaces("s1", listOf(place, other), listOf(wiki("v2")), listOf(SegmentPlace("s1", "wd-Q2", Fixtures.T0, null, 1.0), SegmentPlace("s1", "wd-Q1", Fixtures.T0, 60_000, 2.5)))
        val e = repo.enrichmentsOf("wd-Q1").single()
        assertTrue(e.userApproved, "approval survives a refresh")
        assertEquals("v1", e.summary, "an approved text is not silently replaced")
        assertEquals(listOf("wd-Q1", "wd-Q2"), repo.placesOf("s1").map { it.id }, "ordered by relevance")
        assertEquals(2, repo.segmentPlacesOfTrip("t1").size)
    }

    @Test
    fun `discarding is a tombstone a re-run respects and deleting the trip cascades`() = runTest {
        repo.replaceSegmentPlaces("s1", listOf(place), listOf(wiki("v1")), listOf(SegmentPlace("s1", "wd-Q1", Fixtures.T0, null, 1.0)))
        repo.discard("s1", "wd-Q1")
        assertEquals(0, repo.placesOf("s1").size)
        assertTrue(!repo.hasPlaces("s1"))
        assertEquals(1, repo.enrichmentsOf("wd-Q1").size, "the cache stays")
        val other = place.copy(id = "wd-Q2", name = "Otro", wikidataId = "Q2")
        repo.replaceSegmentPlaces("s1", listOf(place, other), emptyList(), listOf(SegmentPlace("s1", "wd-Q1", Fixtures.T0, null, 3.0), SegmentPlace("s1", "wd-Q2", Fixtures.T0, null, 1.0)))
        assertEquals(listOf("wd-Q2"), repo.placesOf("s1").map { it.id }, "the discarded place does not come back (review I5)")
        assertEquals(0, repo.pruneOrphans(), "a tombstoned place is still referenced")
        db.tripDao().delete("t1")
        assertEquals(0, repo.segmentPlacesOfTrip("t1").size)
        assertEquals(2, repo.pruneOrphans(), "orphans after the trip is gone")
    }

    @Test
    fun `stop place links are written in the same transaction`() = runTest {
        db.stopDao().insertAll(listOf(com.bitacoraviaje.data.db.entity.StopEntity("st", "s1", Fixtures.T0, Fixtures.T0 + 1, 0.0, 0.0, 1.0, null, null)))
        repo.replaceSegmentPlaces("s1", listOf(place), emptyList(), listOf(SegmentPlace("s1", "wd-Q1", Fixtures.T0, null, 1.0)), stopPlaces = mapOf("st" to "wd-Q1"))
        assertEquals("wd-Q1", db.stopDao().getBySegment("s1").single().placeId)
    }
}
