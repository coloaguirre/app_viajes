package com.bitacoraviaje.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bitacoraviaje.data.db.dao.SegmentDao
import com.bitacoraviaje.data.db.dao.StopDao
import com.bitacoraviaje.data.db.dao.TrackPointDao
import com.bitacoraviaje.data.db.dao.TripDao
import com.bitacoraviaje.data.db.entity.SegmentEntity
import com.bitacoraviaje.data.db.entity.SegmentPauseEntity
import com.bitacoraviaje.data.db.entity.StopEntity
import com.bitacoraviaje.data.db.entity.TrackPointEntity
import com.bitacoraviaje.data.db.entity.TripEntity

/**
 * Schema version history (every bump ships a Migration and a MigrationTestHelper test;
 * destructive fallback is forbidden by the "never lose a trip" rule):
 *  1 — Phase 1: trip, segment, segment_pause, track_point, stop.
 */
@Database(
    entities = [TripEntity::class, SegmentEntity::class, SegmentPauseEntity::class, TrackPointEntity::class, StopEntity::class],
    version = BitacoraDatabase.VERSION,
    exportSchema = true,
)
abstract class BitacoraDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun segmentDao(): SegmentDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun stopDao(): StopDao

    companion object {
        const val VERSION = 1
        const val NAME = "bitacora.db"
    }
}
