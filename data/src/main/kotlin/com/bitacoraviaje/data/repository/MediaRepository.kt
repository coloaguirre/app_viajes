package com.bitacoraviaje.data.repository

import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.db.entity.CameraClockOffsetEntity
import com.bitacoraviaje.data.mapper.toDomain
import com.bitacoraviaje.data.mapper.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class MediaRepository @Inject constructor(private val db: BitacoraDatabase) {
    private val dao get() = db.mediaDao()

    suspend fun upsertAll(items: List<MediaItem>) {
        if (items.isNotEmpty()) dao.upsertAll(items.map { it.toEntity() })
    }

    suspend fun getByMediaStoreId(id: Long): MediaItem? = dao.getByMediaStoreId(id)?.toDomain()

    suspend fun get(id: String): MediaItem? = dao.getById(id)?.toDomain()

    fun observeByTrip(tripId: String): Flow<List<MediaItem>> = dao.observeByTrip(tripId).map { l -> l.map { it.toDomain() } }

    fun observeBySegment(segmentId: String): Flow<List<MediaItem>> = dao.observeBySegment(segmentId).map { l -> l.map { it.toDomain() } }

    fun observeReviewQueue(tripId: String): Flow<List<MediaItem>> = dao.observeReviewQueue(tripId).map { l -> l.map { it.toDomain() } }

    suspend fun countByTrip(tripId: String): Int = dao.countByTrip(tripId)

    suspend fun allOfTrip(tripId: String): List<MediaItem> = dao.getByTrip(tripId).map { it.toDomain() }

    /** Confirmed clock offsets per camera ("Make Model") for the trip, applied by the linker on every scan. */
    suspend fun cameraOffsets(tripId: String): Map<String, Long> = db.cameraClockOffsetDao().getByTrip(tripId).associate { it.camera to it.offsetMs }

    suspend fun setCameraOffset(tripId: String, camera: String, offsetMs: Long) = db.cameraClockOffsetDao().upsert(CameraClockOffsetEntity(tripId, camera, offsetMs))

    /** Manual placement from the review screen: wins over any future rescan. */
    suspend fun placeManually(id: String, lat: Double, lon: Double) {
        val m = dao.getById(id)?.toDomain() ?: return
        dao.update(m.copy(linkedLat = lat, linkedLon = lon, linkMethod = LinkMethod.MANUAL, linkConfidence = 1.0, needsReview = false).toEntity())
    }

    suspend fun setExcluded(id: String, excluded: Boolean) {
        val m = dao.getById(id)?.toDomain() ?: return
        dao.update(m.copy(isExcluded = excluded).toEntity())
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val m = dao.getById(id)?.toDomain() ?: return
        dao.update(m.copy(isFavorite = favorite).toEntity())
    }
}
