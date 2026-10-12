package com.bitacoraviaje.app.media

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review M10: the linker's central promises, against an in-memory Room and a fake
 * catalog — rescans never touch user edits, confirmed camera offsets accumulate and
 * survive rescans, and a GPS time stamp confirms a wrong camera clock by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaLinkerTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var tracking: TrackingRepository
    private lateinit var media: MediaRepository
    private val catalog = FakeCatalog()
    private val t0 = 1_760_000_000_000L
    private val now = t0 + 3_600_000
    private var clockNow = t0

    @BeforeTest
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BitacoraDatabase::class.java).allowMainThreadQueries().build()
        tracking = TrackingRepository(db, Clock { clockNow })
        media = MediaRepository(db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun walkSegment(): String {
        val s = tracking.startSession(SegmentType.STAGE, Transport.WALK, RecordingMode.FULL, "UTC", tripTitle = "T")
        // 20 min walk north at 1 m/s, one fix every 10 s.
        tracking.insertPoints((0..120).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 10_000L, lat = -34.6 + i * 10 / 111_320.0, lon = -58.4) })
        clockNow = t0 + 1_200_000 // the segment ends when the walk ends, not at its start
        tracking.finishSegment(s.id)
        return s.tripId
    }

    private fun exifAt(utcMs: Long, camera: String = "CamA", gpsUtcMs: Long? = null, lat: Double? = null, lon: Double? = null): ExifData {
        val f = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneOffset.UTC)
        val g = gpsUtcMs?.let { Instant.ofEpochMilli(it) }
        return ExifData(
            dateTimeOriginal = f.format(Instant.ofEpochMilli(utcMs)), offsetTimeOriginal = "+00:00", subsecOriginal = null,
            gpsDateStamp = g?.let { DateTimeFormatter.ofPattern("yyyy:MM:dd").withZone(ZoneOffset.UTC).format(it) },
            gpsTimeStamp = g?.let { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(it) },
            lat = lat, lon = lon, make = "Synthetic", model = camera,
        )
    }

    private fun photo(id: Long, exif: ExifData?, dateTaken: Long? = null) = MediaCandidate(
        mediaStoreId = id, contentUri = Uri.parse("content://media/external/images/media/$id"), kind = MediaKind.PHOTO,
        displayName = "p$id.jpg", mimeType = "image/jpeg", sizeBytes = 1, width = 10, height = 10, durationMs = null, orientationDeg = null,
        dateTakenMs = dateTaken, dateAddedS = null, dateModifiedS = null, relativePath = "DCIM/Camera", exif = exif,
    )

    @Test
    fun `rescan keeps caption, favourite, exclusion and manual placement`() = runTest {
        val tripId = walkSegment()
        val linker = MediaLinker(catalog, tracking, media)
        catalog.items = listOf(photo(1, exifAt(t0 + 300_000)), photo(2, exifAt(t0 + 400_000)), photo(3, exifAt(t0 + 500_000)))
        linker.linkTrip(tripId, nowUtcMs = now)
        val p1 = assertNotNull(media.getByMediaStoreId(1))
        val p2 = assertNotNull(media.getByMediaStoreId(2))
        val p3 = assertNotNull(media.getByMediaStoreId(3))
        assertEquals(LinkMethod.TRACK_INTERPOLATED, p1.linkMethod)
        media.placeManually(p1.id, -34.0, -58.0)
        media.setExcluded(p2.id, true)
        media.setFavorite(p3.id, true)
        db.mediaDao().update(assertNotNull(db.mediaDao().getById(p3.id)).copy(userCaption = "cumbre"))

        val r = linker.linkTrip(tripId, nowUtcMs = now)
        assertEquals(2, r.skipped, "manual and excluded are skipped")
        val q1 = assertNotNull(media.get(p1.id))
        assertEquals(LinkMethod.MANUAL, q1.linkMethod)
        assertEquals(-34.0, q1.linkedLat)
        assertEquals(true, media.get(p2.id)?.isExcluded)
        val q3 = assertNotNull(media.get(p3.id))
        assertEquals(true, q3.isFavorite)
        assertEquals("cumbre", q3.userCaption)
        assertEquals(3, media.countByTrip(tripId) + 1, "no duplicate rows on rescan (one excluded)")
    }

    @Test
    fun `camera offset accumulates across two fixes and a rescan keeps the shifted times`() = runTest {
        val tripId = walkSegment()
        val linker = MediaLinker(catalog, tracking, media)
        catalog.items = listOf(photo(1, exifAt(t0 + 300_000)), photo(2, exifAt(t0 + 600_000)))
        linker.linkTrip(tripId, nowUtcMs = now)
        linker.applyCameraOffset(tripId, "Synthetic CamA", -120_000, nowUtcMs = now)
        linker.applyCameraOffset(tripId, "Synthetic CamA", -60_000, nowUtcMs = now)
        assertEquals(-180_000L, media.cameraOffsets(tripId)["Synthetic CamA"])
        assertEquals(t0 + 300_000 - 180_000, media.getByMediaStoreId(1)?.takenUtcMs)

        linker.linkTrip(tripId, nowUtcMs = now)
        assertEquals(t0 + 300_000 - 180_000, media.getByMediaStoreId(1)?.takenUtcMs, "rescan applies the stored offset")
        assertEquals(t0 + 600_000 - 180_000, media.getByMediaStoreId(2)?.takenUtcMs)
    }

    @Test
    fun `a GPS time stamp that disagrees with the camera clock confirms the offset automatically`() = runTest {
        val tripId = walkSegment()
        val linker = MediaLinker(catalog, tracking, media)
        // Camera clock 8 min fast; GPS says the truth. No EXIF position so the track decides.
        catalog.items = listOf(photo(1, exifAt(t0 + 300_000 + 480_000, camera = "CamB", gpsUtcMs = t0 + 300_000)))
        linker.linkTrip(tripId, nowUtcMs = now)
        assertEquals(-480_000L, media.cameraOffsets(tripId)["Synthetic CamB"])
        val p = assertNotNull(media.getByMediaStoreId(1))
        assertEquals(t0 + 300_000, p.takenUtcMs)
        assertEquals(LinkMethod.TRACK_INTERPOLATED, p.linkMethod)
    }

    private class FakeCatalog : MediaCatalog {
        var items: List<MediaCandidate> = emptyList()
        override suspend fun queryByTime(fromUtcMs: Long, toUtcMs: Long): List<MediaCandidate> =
            items.filter { c -> (c.dateTakenMs ?: exifMs(c)) in fromUtcMs..toUtcMs }

        private fun exifMs(c: MediaCandidate): Long = c.exif?.dateTimeOriginal?.let {
            java.time.LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).toInstant(ZoneOffset.UTC).toEpochMilli()
        } ?: 0L
    }
}
