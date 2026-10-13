package com.bitacoraviaje.app.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.app.R
import com.bitacoraviaje.core.export.TripJsonReader
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import org.xmlpull.v1.XmlPullParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 3.5: the database stays out of Auto Backup; trip.json is the backup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRulesTest {
    private data class Rule(val section: String, val kind: String, val domain: String, val path: String)

    private fun rules(resId: Int): List<Rule> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val p = ctx.resources.getXml(resId)
        val out = ArrayList<Rule>()
        var section = "root"
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "cloud-backup", "device-transfer", "full-backup-content" -> section = p.name
                    "include", "exclude" -> out += Rule(section, p.name, p.getAttributeValue(null, "domain"), p.getAttributeValue(null, "path"))
                }
            }
            ev = p.next()
        }
        return out
    }

    @Test
    fun `only preferences and the backup folder are included, so the database audio and models stay out`() {
        for ((res, sections) in listOf(R.xml.data_extraction_rules to listOf("cloud-backup", "device-transfer"), R.xml.backup_rules to listOf("full-backup-content"))) {
            val r = rules(res)
            for (s in sections) {
                val includes = r.filter { it.section == s && it.kind == "include" }
                assertTrue(includes.isNotEmpty(), "$s lists includes (everything else is excluded)")
                assertTrue(includes.none { it.domain == "database" }, "$s never includes the database")
                assertTrue(includes.none { it.domain == "file" && it.path.startsWith("models") }, "$s never includes models")
                assertTrue(includes.any { it.domain == "file" && it.path == "backup/" }, "$s includes backup/")
                if (s != "device-transfer") assertTrue(includes.none { it.domain == "file" && it.path.startsWith("audio") }, "$s leaves audio out of the cloud")
            }
        }
    }

    @Test
    fun `closing a segment leaves a readable trip json under files backup`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        try {
            var now = 1_760_000_000_000L
            val tracking = TrackingRepository(db, Clock { now })
            val s = tracking.startSession(SegmentType.TRIP_START, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "Respaldo")
            tracking.insertPoints((0 until 10).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = now + i * 10_000L, lat = -34.6 + i * 1e-4, lon = -58.4) })
            now += 100_000
            tracking.finishSegment(s.id)
            val f = assertNotNull(BackupFiles.write(ctx, ExportLoader(tracking, MediaRepository(db), NotesRepository(db)), s.tripId))
            assertTrue(f.isFile && f.parentFile!!.name == "backup")
            val back = TripJsonReader.read(java.util.zip.GZIPInputStream(f.inputStream()).readBytes().toString(Charsets.UTF_8))
            assertEquals("Respaldo", back.trip.title)
            assertEquals(10, back.segments.single().points.size)
            // Second write replaces atomically (no .tmp left behind).
            assertNotNull(BackupFiles.write(ctx, ExportLoader(tracking, MediaRepository(db), NotesRepository(db)), s.tripId))
            assertEquals(listOf("${s.tripId}.json.gz"), f.parentFile!!.list()!!.toList())
        } finally {
            db.close()
        }
    }
}
