package com.bitacoraviaje.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bitacoraviaje.data.db.dao.MediaDao
import com.bitacoraviaje.data.db.dao.SegmentDao
import com.bitacoraviaje.data.db.dao.StopDao
import com.bitacoraviaje.data.db.dao.TextNoteDao
import com.bitacoraviaje.data.db.dao.VoiceNoteDao
import com.bitacoraviaje.data.db.dao.TrackPointDao
import com.bitacoraviaje.data.db.dao.TripDao
import com.bitacoraviaje.data.db.entity.MediaItemEntity
import com.bitacoraviaje.data.db.entity.SegmentEntity
import com.bitacoraviaje.data.db.entity.SegmentPauseEntity
import com.bitacoraviaje.data.db.entity.StopEntity
import com.bitacoraviaje.data.db.entity.TextNoteEntity
import com.bitacoraviaje.data.db.entity.VoiceNoteEntity
import com.bitacoraviaje.data.db.entity.TrackPointEntity
import com.bitacoraviaje.data.db.entity.TripEntity

/**
 * Schema version history (every bump ships a Migration and a MigrationTestHelper test;
 * destructive fallback is forbidden by the "never lose a trip" rule):
 *  1 — Phase 1: trip, segment, segment_pause, track_point, stop.
 *  2 — item 1.5: track_point.monotonic_ms (clock-change resilience).
 *  3 — item 2.2: media_item (photos and videos linked to the trip).
 *  4 — items 2.4/2.5: voice_note and text_note.
 */
@Database(
    entities = [TripEntity::class, SegmentEntity::class, SegmentPauseEntity::class, TrackPointEntity::class, StopEntity::class, MediaItemEntity::class, VoiceNoteEntity::class, TextNoteEntity::class],
    version = BitacoraDatabase.VERSION,
    exportSchema = true,
)
abstract class BitacoraDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun segmentDao(): SegmentDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun stopDao(): StopDao
    abstract fun mediaDao(): MediaDao
    abstract fun voiceNoteDao(): VoiceNoteDao
    abstract fun textNoteDao(): TextNoteDao

    companion object {
        const val VERSION = 4
        const val NAME = "bitacora.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_point ADD COLUMN monotonic_ms INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS media_item (
                        id TEXT NOT NULL PRIMARY KEY,
                        trip_id TEXT NOT NULL,
                        segment_id TEXT,
                        kind TEXT NOT NULL,
                        source TEXT NOT NULL,
                        content_uri TEXT,
                        mediastore_id INTEGER,
                        display_name TEXT,
                        mime_type TEXT,
                        size_bytes INTEGER,
                        width INTEGER,
                        height INTEGER,
                        duration_ms INTEGER,
                        orientation_deg INTEGER,
                        taken_utc_ms INTEGER NOT NULL,
                        taken_time_source TEXT NOT NULL,
                        exif_lat REAL,
                        exif_lon REAL,
                        camera_make TEXT,
                        camera_model TEXT,
                        linked_lat REAL,
                        linked_lon REAL,
                        link_method TEXT NOT NULL,
                        link_confidence REAL NOT NULL,
                        link_time_gap_ms INTEGER,
                        needs_review INTEGER NOT NULL,
                        sha256 TEXT,
                        user_caption TEXT,
                        is_favorite INTEGER NOT NULL,
                        is_excluded INTEGER NOT NULL,
                        FOREIGN KEY(trip_id) REFERENCES trip(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(segment_id) REFERENCES segment(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_trip_time ON media_item (trip_id, taken_utc_ms)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_media_ms ON media_item (mediastore_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_item_segment_id ON media_item (segment_id)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS voice_note (
                        id TEXT NOT NULL PRIMARY KEY,
                        segment_id TEXT NOT NULL,
                        started_utc_ms INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        lat REAL, lon REAL, h_accuracy_m REAL,
                        audio_path TEXT NOT NULL,
                        audio_format TEXT NOT NULL,
                        transcript TEXT, transcript_edited TEXT, transcript_lang TEXT, transcript_engine TEXT,
                        transcript_status TEXT NOT NULL,
                        transcript_segments_json TEXT,
                        media_item_id TEXT,
                        FOREIGN KEY(segment_id) REFERENCES segment(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_voice_segment_time ON voice_note (segment_id, started_utc_ms)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS text_note (
                        id TEXT NOT NULL PRIMARY KEY,
                        segment_id TEXT NOT NULL,
                        time_utc_ms INTEGER NOT NULL,
                        lat REAL, lon REAL,
                        kind TEXT NOT NULL,
                        text TEXT,
                        FOREIGN KEY(segment_id) REFERENCES segment(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_text_segment_time ON text_note (segment_id, time_utc_ms)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
