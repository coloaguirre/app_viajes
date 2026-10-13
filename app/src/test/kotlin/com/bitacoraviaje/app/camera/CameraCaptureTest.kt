package com.bitacoraviaje.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.app.media.ExifData
import com.bitacoraviaje.app.media.MediaCandidate
import com.bitacoraviaje.app.media.MediaCatalog
import com.bitacoraviaje.app.media.MediaLinker
import com.bitacoraviaje.app.voice.NotePosition
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import com.bitacoraviaje.data.repository.Clock
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 5.2 acceptance: a shot from the app's camera is linked with confidence 1.0, carries EXIF GPS + offset, and the sweep leaves it alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraCaptureTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: BitacoraDatabase
    private lateinit var tracking: TrackingRepository
    private lateinit var media: MediaRepository
    private val t0 = 1_760_000_000_000L // 2025-10-09T08:53:20Z
    private var clockNow = t0

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        tracking = TrackingRepository(db, Clock { clockNow })
        media = MediaRepository(db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun capture(fix: NotePosition?) = CameraCapture(ctx, tracking, media) { fix ?: NotePosition(null, null, null) }

    private fun jpeg(name: String): File = File(ctx.cacheDir, name).also { f -> Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, f.outputStream()) }

    @Test
    fun `exif stamp writes the local time, its offset and the fix, and the row links with confidence one`() {
        val f = jpeg("shot.jpg")
        val loc = Location("test").apply { latitude = -34.6037; longitude = -58.3816; altitude = 25.0 }
        ExifInterface(f).apply { CameraCapture.stamp(this, loc, t0, "America/Argentina/Buenos_Aires"); saveAttributes() }
        val exif = ExifInterface(f)
        assertEquals("2025:10:09 05:53:20", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("-03:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        val ll = assertNotNull(exif.latLong)
        assertEquals(-34.6037, ll[0], 1e-4)
        assertEquals(-58.3816, ll[1], 1e-4)
        assertEquals("+00:00", CameraCapture.offsetString(t0, "UTC"))
        assertEquals("Viaje", CameraCapture.folderName("  "))
        assertEquals("Patagonia 2026 _ norte", CameraCapture.folderName("Patagonia 2026 / norte"))

        val withFix = CameraCapture.mediaItem("t", "s", MediaKind.PHOTO, Uri.parse("content://media/external/images/media/9"), 9, "BV.jpg", "image/jpeg", 100, 16, 12, null, t0, -34.6, -58.4)
        assertEquals(MediaSource.APP_CAMERA, withFix.source)
        assertEquals(LinkMethod.EXIF, withFix.linkMethod)
        assertEquals(1.0, withFix.linkConfidence)
        assertEquals(-34.6 to -58.4, withFix.linkedLat to withFix.linkedLon)
        assertTrue(!withFix.needsReview)
        val noFix = CameraCapture.mediaItem("t", "s", MediaKind.VIDEO, Uri.parse("content://x/1"), 1, null, "video/mp4", null, null, null, 4_000, t0, null, null)
        assertEquals(LinkMethod.NONE, noFix.linkMethod)
        assertTrue(noFix.needsReview, "without a fix the sweep decides later")

        val values = capture(null).contentValues(MediaKind.PHOTO, "Patagonia", t0, "America/Argentina/Buenos_Aires")
        assertEquals("BV_20251009_055320.jpg", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        if (Build.VERSION.SDK_INT >= 29) assertEquals("Pictures/BitacoraViaje/Patagonia", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("Movies/BitacoraViaje/Viaje", capture(null).contentValues(MediaKind.VIDEO, null, t0, "UTC").getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    fun `a registered shot is stored linked by exif with confidence one and the reconciliation sweep does not relink it`() = runTest {
        val s = tracking.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", tripTitle = "Prueba")
        tracking.insertPoints((0..30).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 10_000L, lat = -34.6 + i * 10 / 111_320.0, lon = -58.4) })
        val fix = NotePosition(-34.6010, -58.4, 5f)
        val cap = capture(fix)
        val loc = assertNotNull(cap.location(t0 + 100_000))
        assertEquals(-34.6010, loc.latitude)
        val f = jpeg("registered.jpg")
        // A file uri: MediaStore has nothing to say about it, the EXIF stamp still lands on the file.
        val item = assertNotNull(cap.register(Uri.fromFile(f), MediaKind.PHOTO, s.id, loc, t0 + 100_000))
        assertEquals(LinkMethod.EXIF, item.linkMethod)
        assertEquals(1.0, item.linkConfidence)
        assertEquals(s.id, item.segmentId)
        assertEquals(s.tripId, item.tripId)
        val stored = assertNotNull(media.get(item.id))
        assertEquals(MediaSource.APP_CAMERA, stored.source)
        val exif = ExifInterface(f)
        assertEquals("-03:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertNotNull(exif.latLong, "GPS written into the app's own file")

        // The sweep finds the same MediaStore row later: it must not re-link or duplicate the app's shot.
        val own = assertNotNull(cap.register(Uri.parse("content://media/external/images/media/77"), MediaKind.PHOTO, s.id, loc, t0 + 150_000))
        assertEquals(77L, own.mediaStoreId)
        val catalog = object : MediaCatalog {
            override suspend fun queryByTime(fromUtcMs: Long, toUtcMs: Long) = listOf(
                MediaCandidate(
                    mediaStoreId = 77, contentUri = Uri.parse("content://media/external/images/media/77"), kind = MediaKind.PHOTO, displayName = "BV.jpg", mimeType = "image/jpeg",
                    sizeBytes = 1, width = 16, height = 12, durationMs = null, orientationDeg = null, dateTakenMs = t0 + 150_000, dateAddedS = null, dateModifiedS = null,
                    relativePath = "Pictures/BitacoraViaje/Prueba", exif = ExifData(null, null, null, null, null, -34.6013, -58.4, "Synthetic", "Cam"),
                ),
            )
        }
        clockNow = t0 + 400_000
        tracking.finishSegment(s.id)
        val r = MediaLinker(catalog, tracking, media).linkTrip(s.tripId, nowUtcMs = t0 + 500_000)
        assertEquals(1, r.skipped, "the app's own shot is authoritative")
        val after = assertNotNull(media.get(own.id))
        assertEquals(MediaSource.APP_CAMERA, after.source)
        assertEquals(1.0, after.linkConfidence)
        assertEquals(2, media.countByTrip(s.tripId), "no duplicate row")
        assertNull(media.allOfTrip(s.tripId).firstOrNull { it.source == MediaSource.MEDIASTORE })
    }

    @Test
    fun `a note about this photo carries the media id into the voice bubble`() {
        val i = com.bitacoraviaje.app.voice.VoiceNoteActivity.intent(ctx, mediaItemId = "m-1")
        assertEquals("m-1", i.getStringExtra(com.bitacoraviaje.app.voice.VoiceNoteActivity.EXTRA_MEDIA_ITEM_ID))
        assertNull(com.bitacoraviaje.app.voice.VoiceNoteActivity.intent(ctx).getStringExtra(com.bitacoraviaje.app.voice.VoiceNoteActivity.EXTRA_MEDIA_ITEM_ID))
    }
}
