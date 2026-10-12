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
    fun `current version equals the latest exported schema`() {
        // When VERSION grows, add MIGRATION_1_2 to BitacoraDatabase and call
        // helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2) here.
        assertEquals(1, BitacoraDatabase.VERSION)
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
