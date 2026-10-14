package com.bitacoraviaje.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.bitacoraviaje.data.db.entity.CameraClockOffsetEntity
import com.bitacoraviaje.data.db.entity.MediaItemEntity
import com.bitacoraviaje.data.db.entity.TextNoteEntity
import com.bitacoraviaje.data.db.entity.VoiceNoteEntity
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

    @Query("SELECT * FROM trip WHERE status = 'ACTIVE' AND archived = 0 LIMIT 1")
    suspend fun getActive(): TripEntity?

    /** Deleting a trip cascades to segments, pauses, points and stops. Callers confirm with the user first. */
    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE trip SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE trip SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)
}

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(segment: SegmentEntity)

    @Update
    suspend fun update(segment: SegmentEntity)

    @Query("SELECT * FROM segment WHERE id = :id")
    suspend fun getById(id: String): SegmentEntity?

    @Query("UPDATE segment SET auto_name = :autoName WHERE id = :id")
    suspend fun setAutoName(id: String, autoName: String?)

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

    @Query("SELECT COUNT(*) FROM track_point WHERE segment_id = :segmentId AND is_filtered_out = 0")
    suspend fun countValidBySegment(segmentId: String): Int

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
interface MediaDao {
    /** Upsert by primary key; the unique index on mediastore_id makes duplicates from rescans impossible. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Update
    suspend fun update(item: MediaItemEntity)

    /** New rows only: an existing mediastore_id is left untouched (review I6). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<MediaItemEntity>): List<Long>

    /**
     * Re-link an existing row without clobbering what the user did meanwhile: manual
     * placements and exclusions are skipped by the WHERE clause, caption/favourite are
     * not in the SET list (review I6).
     */
    @Query(
        "UPDATE media_item SET segment_id = :segmentId, taken_utc_ms = :takenUtcMs, taken_time_source = :takenTimeSource, " +
            "exif_lat = :exifLat, exif_lon = :exifLon, camera_make = :cameraMake, camera_model = :cameraModel, " +
            "linked_lat = :linkedLat, linked_lon = :linkedLon, link_method = :linkMethod, link_confidence = :linkConfidence, " +
            "link_time_gap_ms = :linkTimeGapMs, needs_review = :needsReview " +
            "WHERE id = :id AND link_method != 'MANUAL' AND is_excluded = 0",
    )
    suspend fun relink(
        id: String, segmentId: String?, takenUtcMs: Long, takenTimeSource: String, exifLat: Double?, exifLon: Double?,
        cameraMake: String?, cameraModel: String?, linkedLat: Double?, linkedLon: Double?, linkMethod: String,
        linkConfidence: Double, linkTimeGapMs: Long?, needsReview: Boolean,
    ): Int

    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun getById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_item WHERE mediastore_id = :mediaStoreId")
    suspend fun getByMediaStoreId(mediaStoreId: Long): MediaItemEntity?

    @Query("SELECT mediastore_id FROM media_item WHERE mediastore_id IS NOT NULL")
    suspend fun knownMediaStoreIds(): List<Long>

    @Query("SELECT * FROM media_item WHERE trip_id = :tripId AND is_excluded = 0 ORDER BY taken_utc_ms")
    fun observeByTrip(tripId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_item WHERE segment_id = :segmentId AND is_excluded = 0 ORDER BY taken_utc_ms")
    fun observeBySegment(segmentId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_item WHERE trip_id = :tripId ORDER BY taken_utc_ms")
    suspend fun getByTrip(tripId: String): List<MediaItemEntity>

    /** Review queue (DISENO §14.1 item 7): unplaced, low-confidence or flagged. */
    @Query("SELECT * FROM media_item WHERE trip_id = :tripId AND is_excluded = 0 AND (link_method = 'NONE' OR link_confidence < 0.6 OR needs_review = 1) ORDER BY taken_utc_ms")
    fun observeReviewQueue(tripId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT COUNT(*) FROM media_item WHERE trip_id = :tripId AND is_excluded = 0")
    suspend fun countByTrip(tripId: String): Int
}

@Dao
interface VoiceNoteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: VoiceNoteEntity)

    @Update
    suspend fun update(note: VoiceNoteEntity)

    @Query("SELECT * FROM voice_note WHERE id = :id")
    suspend fun getById(id: String): VoiceNoteEntity?

    @Query("SELECT * FROM voice_note WHERE id = :id")
    fun observeById(id: String): Flow<VoiceNoteEntity?>

    @Query("SELECT * FROM voice_note WHERE segment_id = :segmentId ORDER BY started_utc_ms")
    fun observeBySegment(segmentId: String): Flow<List<VoiceNoteEntity>>

    @Query("SELECT * FROM voice_note WHERE segment_id = :segmentId ORDER BY started_utc_ms")
    suspend fun getBySegment(segmentId: String): List<VoiceNoteEntity>

    @Query("SELECT v.* FROM voice_note v JOIN segment s ON s.id = v.segment_id WHERE s.trip_id = :tripId ORDER BY v.started_utc_ms")
    fun observeByTrip(tripId: String): Flow<List<VoiceNoteEntity>>

    @Query("SELECT * FROM voice_note WHERE transcript_status IN ('PENDING','FAILED') ORDER BY started_utc_ms")
    suspend fun pendingTranscriptions(): List<VoiceNoteEntity>

    @Query("DELETE FROM voice_note WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TextNoteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: TextNoteEntity)

    @Update
    suspend fun update(note: TextNoteEntity)

    @Query("SELECT * FROM text_note WHERE segment_id = :segmentId ORDER BY time_utc_ms")
    fun observeBySegment(segmentId: String): Flow<List<TextNoteEntity>>

    @Query("SELECT * FROM text_note WHERE segment_id = :segmentId ORDER BY time_utc_ms")
    suspend fun getBySegment(segmentId: String): List<TextNoteEntity>

    @Query("SELECT t.* FROM text_note t JOIN segment s ON s.id = t.segment_id WHERE s.trip_id = :tripId ORDER BY t.time_utc_ms")
    fun observeByTrip(tripId: String): Flow<List<TextNoteEntity>>

    @Query("DELETE FROM text_note WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface StopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stops: List<StopEntity>)

    @Query("SELECT * FROM stop WHERE segment_id = :segmentId ORDER BY start_utc_ms")
    suspend fun getBySegment(segmentId: String): List<StopEntity>

    @Query("SELECT * FROM stop WHERE segment_id = :segmentId ORDER BY start_utc_ms")
    fun observeBySegment(segmentId: String): Flow<List<StopEntity>>

    /** Phase 4: the place Nominatim named for this stop. */
    @Query("UPDATE stop SET place_id = :placeId WHERE id = :stopId")
    suspend fun setPlace(stopId: String, placeId: String?)

    /** Stops are recomputed when a segment closes; replace the previous detection atomically. */
    @Query("DELETE FROM stop WHERE segment_id = :segmentId")
    suspend fun deleteBySegment(segmentId: String)

    @Transaction
    suspend fun replaceForSegment(segmentId: String, stops: List<StopEntity>) {
        deleteBySegment(segmentId)
        insertAll(stops)
    }
}

@Dao
interface CameraClockOffsetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CameraClockOffsetEntity)

    @Query("SELECT * FROM camera_clock_offset WHERE trip_id = :tripId")
    suspend fun getByTrip(tripId: String): List<CameraClockOffsetEntity>
}

// ---- Phase 4: places ----

@Dao
interface PlaceDao {
    /**
     * Places are keyed by source id; a re-run refreshes the row (newer fetch wins).
     * `@Upsert` (INSERT ... ON CONFLICT DO UPDATE), never REPLACE: REPLACE deletes the old
     * row first and the cascade would wipe the place's enrichments and links.
     */
    @Upsert
    suspend fun upsertAll(places: List<com.bitacoraviaje.data.db.entity.PlaceEntity>)

    @Query("SELECT * FROM place WHERE id = :id")
    suspend fun getById(id: String): com.bitacoraviaje.data.db.entity.PlaceEntity?

    @Query("SELECT p.* FROM place p JOIN segment_place sp ON sp.place_id = p.id WHERE sp.segment_id = :segmentId AND sp.dismissed = 0 ORDER BY sp.relevance DESC, sp.first_seen_utc_ms")
    suspend fun getBySegment(segmentId: String): List<com.bitacoraviaje.data.db.entity.PlaceEntity>

    @Query("SELECT DISTINCT p.* FROM place p JOIN segment_place sp ON sp.place_id = p.id JOIN segment s ON s.id = sp.segment_id WHERE s.trip_id = :tripId AND sp.dismissed = 0")
    suspend fun getByTrip(tripId: String): List<com.bitacoraviaje.data.db.entity.PlaceEntity>

    @Query("SELECT DISTINCT p.* FROM place p JOIN segment_place sp ON sp.place_id = p.id JOIN segment s ON s.id = sp.segment_id WHERE s.trip_id = :tripId AND sp.dismissed = 0")
    fun observeByTrip(tripId: String): Flow<List<com.bitacoraviaje.data.db.entity.PlaceEntity>>

    /** Places no segment references any more (after a trip is deleted) are garbage. */
    @Query("DELETE FROM place WHERE id NOT IN (SELECT place_id FROM segment_place) AND id NOT IN (SELECT place_id FROM stop WHERE place_id IS NOT NULL)")
    suspend fun deleteOrphans(): Int
}

@Dao
interface EnrichmentDao {
    @Upsert
    suspend fun upsertAll(rows: List<com.bitacoraviaje.data.db.entity.EnrichmentEntity>)

    @Query("SELECT * FROM enrichment WHERE place_id = :placeId ORDER BY source_name")
    suspend fun getByPlace(placeId: String): List<com.bitacoraviaje.data.db.entity.EnrichmentEntity>

    @Query("SELECT e.* FROM enrichment e WHERE e.place_id IN (SELECT sp.place_id FROM segment_place sp JOIN segment s ON s.id = sp.segment_id WHERE s.trip_id = :tripId AND sp.dismissed = 0)")
    suspend fun getByTrip(tripId: String): List<com.bitacoraviaje.data.db.entity.EnrichmentEntity>

    @Query("SELECT e.* FROM enrichment e WHERE e.place_id IN (SELECT sp.place_id FROM segment_place sp JOIN segment s ON s.id = sp.segment_id WHERE s.trip_id = :tripId AND sp.dismissed = 0)")
    fun observeByTrip(tripId: String): Flow<List<com.bitacoraviaje.data.db.entity.EnrichmentEntity>>

    @Query("UPDATE enrichment SET user_approved = :approved WHERE id = :id")
    suspend fun setApproved(id: String, approved: Boolean)

    /** User edit or LLM rewrite keeps the source: the fetched text moves to original_summary once (DISENO §11.4). */
    @Query("UPDATE enrichment SET original_summary = COALESCE(original_summary, summary), summary = :summary, llm_rewritten = :rewritten WHERE id = :id")
    suspend fun setSummary(id: String, summary: String, rewritten: Boolean)

    /** Back to the text as fetched. */
    @Query("UPDATE enrichment SET summary = COALESCE(original_summary, summary), original_summary = NULL, llm_rewritten = 0 WHERE id = :id")
    suspend fun restoreSummary(id: String)

    @Query("DELETE FROM enrichment WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WeatherDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<com.bitacoraviaje.data.db.entity.WeatherSampleEntity>)

    @Query("SELECT * FROM weather_sample WHERE segment_id = :segmentId ORDER BY time_utc_ms")
    suspend fun getBySegment(segmentId: String): List<com.bitacoraviaje.data.db.entity.WeatherSampleEntity>

    @Query("SELECT * FROM weather_sample WHERE segment_id = :segmentId ORDER BY time_utc_ms")
    fun observeBySegment(segmentId: String): Flow<List<com.bitacoraviaje.data.db.entity.WeatherSampleEntity>>

    @Query("SELECT w.* FROM weather_sample w JOIN segment s ON s.id = w.segment_id WHERE s.trip_id = :tripId ORDER BY w.time_utc_ms")
    suspend fun getByTrip(tripId: String): List<com.bitacoraviaje.data.db.entity.WeatherSampleEntity>

    @Query("SELECT COUNT(*) FROM weather_sample WHERE segment_id = :segmentId")
    suspend fun countBySegment(segmentId: String): Int

    @Query("DELETE FROM weather_sample WHERE segment_id = :segmentId")
    suspend fun deleteBySegment(segmentId: String)
}

@Dao
interface SegmentPlaceDao {
    @Upsert
    suspend fun upsertAll(rows: List<com.bitacoraviaje.data.db.entity.SegmentPlaceEntity>)

    @Query("SELECT * FROM segment_place WHERE segment_id = :segmentId AND dismissed = 0 ORDER BY relevance DESC")
    suspend fun getBySegment(segmentId: String): List<com.bitacoraviaje.data.db.entity.SegmentPlaceEntity>

    @Query("SELECT place_id FROM segment_place WHERE segment_id = :segmentId AND dismissed = 1")
    suspend fun dismissedPlaceIds(segmentId: String): List<String>

    @Query("SELECT sp.* FROM segment_place sp JOIN segment s ON s.id = sp.segment_id WHERE s.trip_id = :tripId AND sp.dismissed = 0 ORDER BY sp.relevance DESC")
    suspend fun getByTrip(tripId: String): List<com.bitacoraviaje.data.db.entity.SegmentPlaceEntity>

    @Query("SELECT sp.* FROM segment_place sp JOIN segment s ON s.id = sp.segment_id WHERE s.trip_id = :tripId AND sp.dismissed = 0 ORDER BY sp.relevance DESC")
    fun observeByTrip(tripId: String): Flow<List<com.bitacoraviaje.data.db.entity.SegmentPlaceEntity>>

    @Query("SELECT COUNT(*) FROM segment_place WHERE segment_id = :segmentId AND dismissed = 0")
    suspend fun countBySegment(segmentId: String): Int

    /** A re-run rewrites the live links; the user's tombstones stay (review I5). */
    @Query("DELETE FROM segment_place WHERE segment_id = :segmentId AND dismissed = 0")
    suspend fun deleteBySegment(segmentId: String)

    @Query("UPDATE segment_place SET dismissed = 1 WHERE segment_id = :segmentId AND place_id = :placeId")
    suspend fun dismiss(segmentId: String, placeId: String)
}
