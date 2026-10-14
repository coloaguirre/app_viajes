package com.bitacoraviaje.data.repository

import androidx.room.withTransaction
import com.bitacoraviaje.core.model.WeatherSample
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.mapper.toDomain
import com.bitacoraviaje.data.mapper.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Hourly weather along each segment (backlog D3); the table is the cache, one row per hour and segment. */
@Singleton
class WeatherRepository @Inject constructor(private val db: BitacoraDatabase) {
    private val dao get() = db.weatherDao()

    suspend fun hasWeather(segmentId: String): Boolean = dao.countBySegment(segmentId) > 0

    suspend fun samplesOf(segmentId: String): List<WeatherSample> = dao.getBySegment(segmentId).map { it.toDomain() }

    fun observeSamples(segmentId: String): Flow<List<WeatherSample>> = dao.observeBySegment(segmentId).map { l -> l.map { it.toDomain() } }

    suspend fun samplesOfTrip(tripId: String): List<WeatherSample> = dao.getByTrip(tripId).map { it.toDomain() }

    /** A (re)fetch replaces the segment's hours atomically: never half old, half new. */
    suspend fun replace(segmentId: String, samples: List<WeatherSample>) = db.withTransaction {
        dao.deleteBySegment(segmentId)
        if (samples.isNotEmpty()) dao.insertAll(samples.map { it.copy(segmentId = segmentId).toEntity() })
    }
}
