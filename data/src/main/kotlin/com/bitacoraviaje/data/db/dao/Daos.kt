package com.bitacoraviaje.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bitacoraviaje.data.db.entity.SegmentEntity
import com.bitacoraviaje.data.db.entity.SegmentPauseEntity
import com.bitacoraviaje.data.db.entity.StopEntity
import com.bitacoraviaje.data.db.entity.TrackPointEntity
import com.bitacoraviaje.data.db.entity.TripEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trip: TripEntity)

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun getById(id: String): TripEntity?

    @Query("SELECT * FROM trip ORDER BY COALESCE(started_utc_ms, created_utc_ms) DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip WHERE status = 'ACTIVE' LIMIT 1")
    fun observeActive(): Flow<TripEntity?>

    @Query("SELECT * FROM trip WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActive(): TripEntity?

    /** Deleting a trip cascades to segments, pauses, points and stops. Callers confirm with the user first. */
    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(segment: SegmentEntity)

    @Update
    suspend fun update(segment: SegmentEntity)

    @Query("SELECT * FROM segment WHERE id = :id")
    suspend fun getById(id: String): SegmentEntity?

    @Query("SELECT * FROM segment WHERE trip_id = :tripId ORDER BY started_utc_ms")
    fun observeByTrip(tripId: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segment WHERE trip_id = :tripId ORDER BY started_utc_ms")
    suspend fun getByTrip(tripId: String): List<SegmentEntity>

    /** The segment currently recording (or paused), if any. At most one at a time (DISENO §16.2). */
    @Query("SELECT * FROM segment WHERE status IN ('ACTIVE','PAUSED') ORDER BY started_utc_ms DESC LIMIT 1")
    suspend fun getOpen(): SegmentEntity?

    @Query("SELECT * FROM segment WHERE status IN ('ACTIVE','PAUSED') ORDER BY started_utc_ms DESC LIMIT 1")
    fun observeOpen(): Flow<SegmentEntity?>

    /** Every open segment of a trip, children first (newest start first). */
    @Query("SELECT * FROM segment WHERE trip_id = :tripId AND status IN ('ACTIVE','PAUSED') ORDER BY started_utc_ms DESC")
    suspend fun getOpenByTrip(tripId: String): List<SegmentEntity>

    @Insert
    suspend fun insertPause(pause: SegmentPauseEntity)

    @Update
    suspend fun updatePause(pause: SegmentPauseEntity)

    @Query("SELECT * FROM segment_pause WHERE segment_id = :segmentId AND resumed_utc_ms IS NULL ORDER BY paused_utc_ms DESC LIMIT 1")
    suspend fun getOpenPause(segmentId: String): SegmentPauseEntity?

    @Query("SELECT * FROM segment_pause WHERE segment_id = :segmentId ORDER BY paused_utc_ms")
    suspend fun getPauses(segmentId: String): List<SegmentPauseEntity>
}

@Dao
interface TrackPointDao {
    /** Batched insert: the service buffers points and flushes every N points or T seconds (DISENO §6.3). */
    @Insert
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_point WHERE segment_id = :segmentId ORDER BY time_utc_ms")
    suspend fun getBySegment(segmentId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_point WHERE segment_id = :segmentId AND is_filtered_out = 0 ORDER BY time_utc_ms")
    suspend fun getValidBySegment(segmentId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_point WHERE segment_id = :segmentId AND is_filtered_out = 0 ORDER BY time_utc_ms")
    fun observeValidBySegment(segmentId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT COUNT(*) FROM track_point WHERE segment_id = :segmentId")
    suspend fun countBySegment(segmentId: String): Int

    @Query("SELECT * FROM track_point WHERE segment_id = :segmentId AND is_filtered_out = 0 ORDER BY time_utc_ms DESC LIMIT 1")
    suspend fun getLastValid(segmentId: String): TrackPointEntity?

    @Query("UPDATE track_point SET is_simplified_keep = 0 WHERE segment_id = :segmentId")
    suspend fun clearSimplified(segmentId: String)

    /** Callers chunk [ids] (SQLite < 3.32 allows 999 bound variables; API 26-30 ship older SQLite). */
    @Query("UPDATE track_point SET is_simplified_keep = 1 WHERE id IN (:ids)")
    suspend fun markKeep(ids: List<Long>)

    @Transaction
    suspend fun markSimplified(segmentId: String, keepIds: List<Long>) {
        clearSimplified(segmentId)
        keepIds.chunked(500).forEach { markKeep(it) }
    }
}

@Dao
interface StopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stops: List<StopEntity>)

    @Query("SELECT * FROM stop WHERE segment_id = :segmentId ORDER BY start_utc_ms")
    suspend fun getBySegment(segmentId: String): List<StopEntity>

    @Query("SELECT * FROM stop WHERE segment_id = :segmentId ORDER BY start_utc_ms")
    fun observeBySegment(segmentId: String): Flow<List<StopEntity>>

    /** Stops are recomputed when a segment closes; replace the previous detection atomically. */
    @Query("DELETE FROM stop WHERE segment_id = :segmentId")
    suspend fun deleteBySegment(segmentId: String)

    @Transaction
    suspend fun replaceForSegment(segmentId: String, stops: List<StopEntity>) {
        deleteBySegment(segmentId)
        insertAll(stops)
    }
}
