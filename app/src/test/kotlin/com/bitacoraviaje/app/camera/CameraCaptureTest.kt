package com.bitacoraviaje.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
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

    private fun jpeg(name: String): File = File(ctx.cacheDir, name).also { f -> f.outputStream().use { Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) } }

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
        assertEquals("+05:45", CameraCapture.offsetString(t0, "Asia/Kathmandu"))
        // CameraX already wrote the shutter instant: the stamp keeps it and only adds the offset (review I5).
        val f2 = jpeg("shot2.jpg")
        ExifInterface(f2).apply { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2025:10:09 05:53:25"); saveAttributes() }
        ExifInterface(f2).apply { CameraCapture.stamp(this, null, t0, "America/Argentina/Buenos_Aires"); saveAttributes() }
        assertEquals("2025:10:09 05:53:25", ExifInterface(f2).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("-03:00", ExifInterface(f2).getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertNull(ExifInterface(f2).latLong)
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
        assertEquals("Pictures/BitacoraViaje/Patagonia", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)) // @Config(sdk = 35)
        assertEquals("Movies/BitacoraViaje/Viaje", capture(null).contentValues(MediaKind.VIDEO, null, t0, "UTC").getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    fun `a registered shot is stored linked by exif with confidence one and the reconciliation sweep does not relink it`() = runTest {
        val s = tracking.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", tripTitle = "Prueba")
        tracking.insertPoints((0..30).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 10_000L, lat = -34.6 + i * 10 / 111_320.0, lon = -58.4) })
        val fix = NotePosition(-34.6010, -58.4, 5f, fixUtcMs = t0 + 90_000)
        val cap = capture(fix)
        val loc = assertNotNull(cap.location(t0 + 100_000))
        assertEquals(-34.6010, loc.latitude)
        assertEquals(t0 + 90_000, loc.time, "the GPS time stamp is the fix's, not the tap's")
        assertNull(capture(fix.copy(fixUtcMs = t0 - 200_000)).location(t0 + 100_000), "a fix older than 2 min is no fix (review I1)")
        assertNull(capture(NotePosition(-34.6, null, null)).location(t0), "lat without lon is no fix")
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
    fun `a shot without a fix waits for the sweep, which links it by time and keeps it as the app's own`() = runTest {
        val s = tracking.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "Sin fix")
        tracking.insertPoints((0..30).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 10_000L, lat = -34.6 + i * 10 / 111_320.0, lon = -58.4) })
        val cap = capture(null)
        val own = assertNotNull(cap.register(Uri.parse("content://media/external/images/media/78"), MediaKind.PHOTO, s.id, null, t0 + 150_000))
        assertEquals(LinkMethod.NONE, own.linkMethod)
        assertTrue(own.needsReview)
        val catalog = object : MediaCatalog {
            override suspend fun queryByTime(fromUtcMs: Long, toUtcMs: Long) = listOf(
                MediaCandidate(
                    mediaStoreId = 78, contentUri = Uri.parse("content://media/external/images/media/78"), kind = MediaKind.PHOTO, displayName = "BV.jpg", mimeType = "image/jpeg",
                    sizeBytes = 1, width = 16, height = 12, durationMs = null, orientationDeg = null, dateTakenMs = t0 + 150_000, dateAddedS = null, dateModifiedS = null,
                    relativePath = "Pictures/BitacoraViaje/Sin fix", exif = null,
                ),
            )
        }
        clockNow = t0 + 400_000
        tracking.finishSegment(s.id)
        val r = MediaLinker(catalog, tracking, media).linkTrip(s.tripId, nowUtcMs = t0 + 500_000)
        assertEquals(0, r.skipped)
        val after = assertNotNull(media.get(own.id))
        assertEquals(LinkMethod.TRACK_INTERPOLATED, after.linkMethod, "linked by time by the sweep (review B1)")
        assertEquals(MediaSource.APP_CAMERA, after.source, "still the app's own shot")
        assertEquals(1, media.countByTrip(s.tripId))
    }

    @Test
    fun `when the sweep runs first the app's registration still wins without a duplicate`() = runTest {
        val s = tracking.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "Carrera")
        tracking.insertPoints((0..30).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 10_000L, lat = -34.6 + i * 10 / 111_320.0, lon = -58.4) })
        val uri = Uri.parse("content://media/external/images/media/79")
        val catalog = object : MediaCatalog {
            override suspend fun queryByTime(fromUtcMs: Long, toUtcMs: Long) = listOf(
                MediaCandidate(mediaStoreId = 79, contentUri = uri, kind = MediaKind.PHOTO, displayName = "BV.jpg", mimeType = "image/jpeg", sizeBytes = 1, width = 16, height = 12, durationMs = null, orientationDeg = null, dateTakenMs = t0 + 150_000, dateAddedS = null, dateModifiedS = null, relativePath = "Pictures/BitacoraViaje/Carrera", exif = null),
            )
        }
        MediaLinker(catalog, tracking, media).linkTrip(s.tripId, listOf(s.id), nowUtcMs = t0 + 160_000)
        assertEquals(MediaSource.MEDIASTORE, assertNotNull(media.getByMediaStoreId(79)).source)
        val fix = NotePosition(-34.6013, -58.4, 5f, fixUtcMs = t0 + 150_000)
        val own = assertNotNull(capture(fix).register(uri, MediaKind.PHOTO, s.id, capture(fix).location(t0 + 150_000), t0 + 150_000))
        val row = assertNotNull(media.getByMediaStoreId(79))
        assertEquals(own.id, row.id)
        assertEquals(MediaSource.APP_CAMERA, row.source)
        assertEquals(1.0, row.linkConfidence)
        assertEquals(1, media.countByTrip(s.tripId), "one row per MediaStore id (review M5)")
    }

    @Test
    fun `a note about this photo carries the media id into the voice bubble`() {
        val i = com.bitacoraviaje.app.voice.VoiceNoteActivity.intent(ctx, mediaItemId = "m-1")
        assertEquals("m-1", i.getStringExtra(com.bitacoraviaje.app.voice.VoiceNoteActivity.EXTRA_MEDIA_ITEM_ID))
        assertNull(com.bitacoraviaje.app.voice.VoiceNoteActivity.intent(ctx).getStringExtra(com.bitacoraviaje.app.voice.VoiceNoteActivity.EXTRA_MEDIA_ITEM_ID))
    }
}
