package com.bitacoraviaje.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bitacoraviaje.data.db.dao.CameraClockOffsetDao
import com.bitacoraviaje.data.db.dao.EnrichmentDao
import com.bitacoraviaje.data.db.dao.PlaceDao
import com.bitacoraviaje.data.db.dao.SegmentPlaceDao
import com.bitacoraviaje.data.db.dao.WeatherDao
import com.bitacoraviaje.data.db.dao.MediaDao
import com.bitacoraviaje.data.db.dao.SegmentDao
import com.bitacoraviaje.data.db.dao.StopDao
import com.bitacoraviaje.data.db.dao.TextNoteDao
import com.bitacoraviaje.data.db.dao.VoiceNoteDao
import com.bitacoraviaje.data.db.dao.TrackPointDao
import com.bitacoraviaje.data.db.dao.TripDao
import com.bitacoraviaje.data.db.entity.CameraClockOffsetEntity
import com.bitacoraviaje.data.db.entity.EnrichmentEntity
import com.bitacoraviaje.data.db.entity.PlaceEntity
import com.bitacoraviaje.data.db.entity.SegmentPlaceEntity
import com.bitacoraviaje.data.db.entity.WeatherSampleEntity
import com.bitacoraviaje.data.db.entity.WeatherFetchEntity
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
 *  5 — item 2.7: camera_clock_offset (per-camera clock fix that survives rescans).
 *  6 — item 3.6: segment.auto_name; camera_clock_offset gets its FK to trip; voice_note.media_item_id indexed.
 *  7 — item 4.1: place, enrichment and segment_place (internet enrichment cache, DISENO §11).
 *  8 — item 4.3: enrichment.original_summary (source text kept under an edit or LLM rewrite).
 *  9 — PR #8 review: segment_place.dismissed (a discarded place stays discarded across re-runs).
 */
@Database(
    entities = [TripEntity::class, SegmentEntity::class, SegmentPauseEntity::class, TrackPointEntity::class, StopEntity::class, MediaItemEntity::class, VoiceNoteEntity::class, TextNoteEntity::class, CameraClockOffsetEntity::class, PlaceEntity::class, EnrichmentEntity::class, SegmentPlaceEntity::class, WeatherSampleEntity::class, WeatherFetchEntity::class],
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
    abstract fun cameraClockOffsetDao(): CameraClockOffsetDao
    abstract fun placeDao(): PlaceDao
    abstract fun enrichmentDao(): EnrichmentDao
    abstract fun segmentPlaceDao(): SegmentPlaceDao
    abstract fun weatherDao(): WeatherDao

    companion object {
        const val VERSION = 12
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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS camera_clock_offset (
                        trip_id TEXT NOT NULL,
                        camera TEXT NOT NULL,
                        offset_ms INTEGER NOT NULL,
                        PRIMARY KEY(trip_id, camera)
                    )""",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segment ADD COLUMN auto_name TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_voice_media ON voice_note (media_item_id)")
                // SQLite cannot add a foreign key in place: rebuild the small table.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS camera_clock_offset_new (
                        trip_id TEXT NOT NULL, camera TEXT NOT NULL, offset_ms INTEGER NOT NULL,
                        PRIMARY KEY(trip_id, camera),
                        FOREIGN KEY(trip_id) REFERENCES trip(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL("INSERT OR IGNORE INTO camera_clock_offset_new (trip_id, camera, offset_ms) SELECT c.trip_id, c.camera, c.offset_ms FROM camera_clock_offset c JOIN trip t ON t.id = c.trip_id")
                db.execSQL("DROP TABLE camera_clock_offset")
                db.execSQL("ALTER TABLE camera_clock_offset_new RENAME TO camera_clock_offset")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS place (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, kind TEXT,
                        country_code TEXT, admin1 TEXT, admin2 TEXT, locality TEXT, osm_type TEXT, osm_id INTEGER,
                        wikidata_id TEXT, wikipedia_title TEXT, wikipedia_lang TEXT, source TEXT NOT NULL, fetched_utc_ms INTEGER
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS enrichment (
                        id TEXT NOT NULL PRIMARY KEY, place_id TEXT NOT NULL, lang TEXT NOT NULL, title TEXT, summary TEXT NOT NULL,
                        source_name TEXT NOT NULL, source_url TEXT NOT NULL, license TEXT, thumbnail_url TEXT,
                        llm_rewritten INTEGER NOT NULL DEFAULT 0, user_approved INTEGER NOT NULL DEFAULT 0, fetched_utc_ms INTEGER NOT NULL,
                        FOREIGN KEY(place_id) REFERENCES place(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_enrichment_place ON enrichment (place_id)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS segment_place (
                        segment_id TEXT NOT NULL, place_id TEXT NOT NULL, first_seen_utc_ms INTEGER NOT NULL, dwell_ms INTEGER, relevance REAL,
                        PRIMARY KEY(segment_id, place_id),
                        FOREIGN KEY(segment_id) REFERENCES segment(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(place_id) REFERENCES place(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_segment_place_place ON segment_place (place_id)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE enrichment ADD COLUMN original_summary TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segment_place ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Backlog D1: trips can be archived (hidden from the main list). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Backlog D3: hourly weather samples per segment (Open-Meteo), fetched after the segment closes. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weather_sample` (`id` TEXT NOT NULL, `segment_id` TEXT NOT NULL, `time_utc_ms` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                        "`temperature_c` REAL, `apparent_c` REAL, `precipitation_mm` REAL, `wind_kmh` REAL, `cloud_pct` INTEGER, `weather_code` INTEGER, `source` TEXT NOT NULL, `fetched_utc_ms` INTEGER, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`segment_id`) REFERENCES `segment`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_weather_segment_time` ON `weather_sample` (`segment_id`, `time_utc_ms`)")
            }
        }

        /** Backlog D3 (review I4): a segment looked up without any usable hour must not be looked up forever. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weather_fetch` (`segment_id` TEXT NOT NULL, `fetched_utc_ms` INTEGER NOT NULL, `hours` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`segment_id`), FOREIGN KEY(`segment_id`) REFERENCES `segment`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
    }
}
