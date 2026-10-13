package com.bitacoraviaje.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schema guard. Version 1 has no migration yet; this test pins the exported
 * schema (data/schemas/) and forces every future bump to add a Migration plus a
 * `migrate(1, N)` case here. Destructive fallback is never acceptable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BitacoraDatabase::class.java)

    @Test
    fun `exported schema v1 creates the five phase-1 tables`() {
        val db = helper.createDatabase(DB_NAME, 1)
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }
        assertTrue(tables.containsAll(setOf("trip", "segment", "segment_pause", "track_point", "stop")), "tables: $tables")
        // Foreign-key enforcement is Room's open callback, not the raw schema: covered by DaoTest.
        db.close()
    }

    @Test
    fun `migrate 1 to 2 keeps rows and adds monotonic_ms`() {
        helper.createDatabase(DB_NAME, 1).use { v1 ->
            v1.execSQL("INSERT INTO trip (id,title,created_utc_ms,status) VALUES ('t','T',1,'ACTIVE')")
            v1.execSQL("INSERT INTO segment (id,trip_id,type,started_utc_ms,tz_id,sampling_profile,recording_mode,auto_created,status) VALUES ('s','t','STAGE',1,'UTC','WALK','FULL',0,'ACTIVE')")
            v1.execSQL("INSERT INTO track_point (segment_id,time_utc_ms,lat,lon,is_mock,is_filtered_out,is_simplified_keep) VALUES ('s',5,1.0,2.0,0,0,1)")
        }
        val v2 = helper.runMigrationsAndValidate(DB_NAME, 2, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v2.query("SELECT monotonic_ms, lat FROM track_point").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0), "new column defaults to NULL for old rows")
            assertEquals(1.0, c.getDouble(1))
        }
        v2.close()
    }

    @Test
    fun `migrate 2 to 3 adds media_item and keeps points`() {
        helper.createDatabase(DB_NAME, 2).use { v2 ->
            v2.execSQL("INSERT INTO trip (id,title,created_utc_ms,status) VALUES ('t','T',1,'ACTIVE')")
            v2.execSQL("INSERT INTO segment (id,trip_id,type,started_utc_ms,tz_id,sampling_profile,recording_mode,auto_created,status) VALUES ('s','t','STAGE',1,'UTC','WALK','FULL',0,'ACTIVE')")
            v2.execSQL("INSERT INTO track_point (segment_id,time_utc_ms,lat,lon,is_mock,monotonic_ms,is_filtered_out,is_simplified_keep) VALUES ('s',5,1.0,2.0,0,7,0,1)")
        }
        val v3 = helper.runMigrationsAndValidate(DB_NAME, 3, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v3.query("SELECT COUNT(*) FROM track_point").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        v3.query("SELECT COUNT(*) FROM media_item").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        v3.close()
    }

    @Test
    fun `migrate 3 to 4 adds voice_note and text_note`() {
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            v3.execSQL("INSERT INTO trip (id,title,created_utc_ms,status) VALUES ('t','T',1,'ACTIVE')")
            v3.execSQL("INSERT INTO segment (id,trip_id,type,started_utc_ms,tz_id,sampling_profile,recording_mode,auto_created,status) VALUES ('s','t','STAGE',1,'UTC','WALK','FULL',0,'ACTIVE')")
        }
        val v4 = helper.runMigrationsAndValidate(DB_NAME, 4, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v4.execSQL("INSERT INTO voice_note (id,segment_id,started_utc_ms,duration_ms,audio_path,audio_format,transcript_status) VALUES ('v','s',5,1000,'audio/t/v.m4a','m4a-aac','PENDING')")
        v4.execSQL("INSERT INTO text_note (id,segment_id,time_utc_ms,kind) VALUES ('n','s',6,'HIGHLIGHT')")
        v4.query("SELECT COUNT(*) FROM voice_note").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        v4.close()
    }

    @Test
    fun `migrate 4 to 5 adds camera_clock_offset keyed by trip and camera`() {
        helper.createDatabase(DB_NAME, 4).use { v4 ->
            v4.execSQL("INSERT INTO trip (id,title,created_utc_ms,status) VALUES ('t','T',1,'ACTIVE')")
        }
        val v5 = helper.runMigrationsAndValidate(DB_NAME, 5, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v5.execSQL("INSERT INTO camera_clock_offset (trip_id,camera,offset_ms) VALUES ('t','Canon EOS',3720000)")
        v5.execSQL("INSERT OR REPLACE INTO camera_clock_offset (trip_id,camera,offset_ms) VALUES ('t','Canon EOS',60000)")
        v5.query("SELECT offset_ms FROM camera_clock_offset WHERE trip_id='t' AND camera='Canon EOS'").use { c -> c.moveToFirst(); assertEquals(60000L, c.getLong(0)) }
        v5.close()
    }

    @Test
    fun `migrate 5 to 6 adds auto_name, keeps camera offsets of existing trips and drops orphans`() {
        helper.createDatabase(DB_NAME, 5).use { v5 ->
            v5.execSQL("INSERT INTO trip (id,title,created_utc_ms,status) VALUES ('t','T',1,'ACTIVE')")
            v5.execSQL("INSERT INTO segment (id,trip_id,type,started_utc_ms,tz_id,sampling_profile,recording_mode,auto_created,status) VALUES ('s','t','STAGE',1,'UTC','WALK','FULL',0,'FINISHED')")
            v5.execSQL("INSERT INTO camera_clock_offset (trip_id,camera,offset_ms) VALUES ('t','Cam',60000)")
            v5.execSQL("INSERT INTO camera_clock_offset (trip_id,camera,offset_ms) VALUES ('gone','Cam',1)")
        }
        val v6 = helper.runMigrationsAndValidate(DB_NAME, 6, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v6.query("SELECT auto_name FROM segment WHERE id='s'").use { c -> c.moveToFirst(); assertEquals(null, c.getString(0)) }
        v6.execSQL("UPDATE segment SET auto_name='Sendero' WHERE id='s'")
        v6.query("SELECT COUNT(*) FROM camera_clock_offset").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0), "orphan offset of a missing trip dropped") }
        // The rebuilt table carries the foreign key (cascade itself is Room's open callback, as in DaoTest).
        v6.query("PRAGMA foreign_key_list(camera_clock_offset)").use { c -> assertTrue(c.moveToFirst()); assertEquals("trip", c.getString(c.getColumnIndexOrThrow("table"))) }
        v6.query("PRAGMA index_list(voice_note)").use { c ->
            val names = generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toList()
            assertTrue("idx_voice_media" in names, "voice_note.media_item_id indexed: $names")
        }
        v6.close()
    }

    @Test
    fun `migrate 6 to 7 adds place, enrichment and segment_place with cascading keys`() {
        helper.createDatabase(DB_NAME, 6).use { v6 ->
            v6.execSQL("INSERT INTO trip (id,title,created_utc_ms,status) VALUES ('t','T',1,'ACTIVE')")
            v6.execSQL("INSERT INTO segment (id,trip_id,type,started_utc_ms,tz_id,sampling_profile,recording_mode,auto_created,status) VALUES ('s','t','STAGE',1,'UTC','WALK','FULL',0,'FINISHED')")
        }
        val v7 = helper.runMigrationsAndValidate(DB_NAME, 7, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v7.execSQL("INSERT INTO place (id,name,lat,lon,kind,source) VALUES ('wd-Q1','Pueblo',-49.3,-72.9,'town','NOMINATIM')")
        v7.execSQL("INSERT INTO enrichment (id,place_id,lang,summary,source_name,source_url,fetched_utc_ms) VALUES ('e1','wd-Q1','es','Texto','Wikipedia','https://es.wikipedia.org/wiki/X',5)")
        v7.execSQL("INSERT INTO segment_place (segment_id,place_id,first_seen_utc_ms,relevance) VALUES ('s','wd-Q1',2,1.5)")
        v7.query("SELECT llm_rewritten, user_approved FROM enrichment WHERE id='e1'").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1)) }
        v7.query("PRAGMA foreign_key_list(segment_place)").use { c ->
            val parents = generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("table")) else null }.toSet()
            assertEquals(setOf("segment", "place"), parents)
        }
        v7.query("PRAGMA index_list(enrichment)").use { c ->
            val names = generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toList()
            assertTrue("idx_enrichment_place" in names, "enrichment.place_id indexed: $names")
        }
        v7.close()
    }

    @Test
    fun `migrate 7 to 8 adds original_summary and keeps texts`() {
        helper.createDatabase(DB_NAME, 7).use { v7 ->
            v7.execSQL("INSERT INTO place (id,name,lat,lon,source) VALUES ('wd-Q1','Pueblo',-49.3,-72.9,'WIKIPEDIA')")
            v7.execSQL("INSERT INTO enrichment (id,place_id,lang,summary,source_name,source_url,fetched_utc_ms) VALUES ('e1','wd-Q1','es','Texto','Wikipedia','https://es.wikipedia.org/wiki/X',5)")
        }
        val v8 = helper.runMigrationsAndValidate(DB_NAME, 8, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v8.query("SELECT summary, original_summary FROM enrichment WHERE id='e1'").use { c -> c.moveToFirst(); assertEquals("Texto", c.getString(0)); assertTrue(c.isNull(1)) }
        v8.close()
    }

    @Test
    fun `migrate 8 to 9 adds dismissed defaulting to false`() {
        helper.createDatabase(DB_NAME, 8).use { v8 ->
            v8.execSQL("INSERT INTO trip (id,title,created_utc_ms,status) VALUES ('t','T',1,'ACTIVE')")
            v8.execSQL("INSERT INTO segment (id,trip_id,type,started_utc_ms,tz_id,sampling_profile,recording_mode,auto_created,status) VALUES ('s','t','STAGE',1,'UTC','WALK','FULL',0,'FINISHED')")
            v8.execSQL("INSERT INTO place (id,name,lat,lon,source) VALUES ('wd-Q1','Pueblo',-49.3,-72.9,'WIKIPEDIA')")
            v8.execSQL("INSERT INTO segment_place (segment_id,place_id,first_seen_utc_ms) VALUES ('s','wd-Q1',2)")
        }
        val v9 = helper.runMigrationsAndValidate(DB_NAME, 9, true, *BitacoraDatabase.ALL_MIGRATIONS)
        v9.query("SELECT dismissed FROM segment_place").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        v9.close()
    }

    @Test
    fun `current version has an exported schema and every bump a migration`() {
        assertEquals(9, BitacoraDatabase.VERSION)
        assertEquals(BitacoraDatabase.VERSION - 1, BitacoraDatabase.ALL_MIGRATIONS.size)
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
