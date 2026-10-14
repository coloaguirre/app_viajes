package com.bitacoraviaje.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.WeatherSample
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.db.Fixtures
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Backlog D3: the weather table as a per-segment cache that a refresh replaces whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherRepositoryTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var repo: WeatherRepository

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BitacoraDatabase::class.java).allowMainThreadQueries().build()
        repo = WeatherRepository(db)
        db.tripDao().insert(Fixtures.trip())
        db.segmentDao().insert(Fixtures.segment())
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun sample(i: Int, temp: Double) = WeatherSample("w$i", "s1", Fixtures.T0 + i * 3_600_000L, -34.6, -58.4, temp, temp - 1, 0.0, 10.0, 20, 1)

    @Test
    fun `replace swaps the hours of a segment atomically and the trip query joins them`() = runTest {
        assertFalse(repo.hasWeather("s1"))
        repo.replace("s1", listOf(sample(0, 10.0), sample(1, 11.0)))
        assertTrue(repo.hasWeather("s1"))
        assertEquals(listOf(10.0, 11.0), repo.samplesOf("s1").map { it.temperatureC })
        repo.replace("s1", listOf(sample(5, 20.0)))
        assertEquals(listOf("w5"), repo.observeSamples("s1").first().map { it.id }, "the old hours are gone")
        assertEquals(listOf("w5"), repo.samplesOfTrip("t1").map { it.id })
        repo.replace("s1", emptyList())
        assertFalse(repo.hasWeather("s1"))
    }
}
