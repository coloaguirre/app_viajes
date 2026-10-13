package com.bitacoraviaje.app.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.bitacoraviaje.app.voice.PositionProvider
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DISENO §7.8 / PLAN 5.2: what happens after CameraX saved a photo or a video into MediaStore.
 * The file is the app's own, so it gets the position of the moment and the time-zone offset in
 * its EXIF (`OffsetTimeOriginal`, which CameraX does not write), and the `media_item` row is
 * created right away with `source = APP_CAMERA`, `link_method = EXIF`, confidence 1.0: the
 * instant and the place are known exactly. The reconciliation sweep never re-links these rows.
 */
@Singleton
class CameraCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val positions: PositionProvider,
) {
    /** Outcome of a shot's registration, for whoever is still listening (the camera screen may already be gone). */
    data class Registered(val uri: Uri, val kind: MediaKind, val item: MediaItem?, val withoutFix: Boolean)

    // Registration outlives the camera route: CameraX's callback can arrive after the user left (review I3 of PR #13).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _registered = MutableSharedFlow<Registered>(replay = 1, extraBufferCapacity = 8)
    val registered: SharedFlow<Registered> = _registered.asSharedFlow()

    /** Registers in the application scope and publishes the result on [registered]. */
    fun enqueue(uri: Uri, kind: MediaKind, segmentId: String, location: Location?, takenUtcMs: Long) {
        scope.launch {
            val item = runCatching { register(uri, kind, segmentId, location, takenUtcMs) }.onFailure { Log.w(TAG, "camera register failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
            _registered.emit(Registered(uri, kind, item, location == null))
        }
    }

    /** Where the shot goes and how it is named, as MediaStore wants it (API 29+: relative path; older: default folder). */
    fun contentValues(kind: MediaKind, tripTitle: String?, takenUtcMs: Long, tzId: String): ContentValues {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone(tzId)).format(Instant.ofEpochMilli(takenUtcMs))
        val folder = folderName(tripTitle)
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, if (kind == MediaKind.PHOTO) "BV_$stamp.jpg" else "BV_$stamp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, if (kind == MediaKind.PHOTO) "image/jpeg" else "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (kind == MediaKind.PHOTO) "Pictures" else "Movies") + "/$APP_FOLDER/$folder")
            }
        }
    }

    /**
     * The fix CameraX writes into the file's GPS tags. Null when there is no position or the newest one is
     * older than [MAX_FIX_AGE_MS]: a stale fix must not become "exact place, confidence 1.0" in a JPEG
     * forever (review I1); the sweep then links the shot by time like any other photo.
     */
    suspend fun location(nowUtcMs: Long = System.currentTimeMillis()): Location? {
        val pos = positions.position(nowUtcMs)
        val lat = pos.lat ?: return null
        val lon = pos.lon ?: return null
        val fixAt = pos.fixUtcMs
        if (fixAt != null && nowUtcMs - fixAt > MAX_FIX_AGE_MS) return null
        return Location("bitacora").apply {
            latitude = lat
            longitude = lon
            pos.accuracyM?.let { accuracy = it }
            time = fixAt ?: nowUtcMs
        }
    }

    /** Registers a saved shot: EXIF offset stamp (photos), MediaStore metadata, and the linked row. */
    suspend fun register(uri: Uri, kind: MediaKind, segmentId: String, location: Location?, takenUtcMs: Long): MediaItem? = withContext(Dispatchers.IO) {
        val segment = tracking.getSegment(segmentId) ?: return@withContext null
        if (kind == MediaKind.PHOTO) stampExif(uri, location, takenUtcMs, segment.tzId)
        val meta = readMediaStore(uri, kind) // after the stamp: the size is the final one (review M2)
        val item = mediaItem(
            tripId = segment.tripId, segmentId = segmentId, kind = kind, uri = uri, mediaStoreId = meta.id, displayName = meta.displayName,
            mimeType = meta.mimeType, sizeBytes = meta.sizeBytes, width = meta.width, height = meta.height, durationMs = meta.durationMs,
            takenUtcMs = takenUtcMs, lat = location?.latitude, lon = location?.longitude,
        )
        media.upsertAll(listOf(item))
        Log.i(TAG, "camera ${kind.name.lowercase()} registered id=${item.id} linked=${item.linkMethod} conf=${item.linkConfidence}")
        item
    }

    private data class Meta(val id: Long?, val displayName: String?, val mimeType: String?, val sizeBytes: Long?, val width: Int?, val height: Int?, val durationMs: Long?)

    private fun readMediaStore(uri: Uri, kind: MediaKind): Meta {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
        val proj = arrayListOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
        if (kind == MediaKind.VIDEO) proj += MediaStore.Video.Media.DURATION
        return runCatching {
            context.contentResolver.query(uri, proj.toTypedArray(), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getString(it) }
                fun lng(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getLong(it) }
                Meta(id, str(MediaStore.MediaColumns.DISPLAY_NAME), str(MediaStore.MediaColumns.MIME_TYPE), lng(MediaStore.MediaColumns.SIZE), lng(MediaStore.MediaColumns.WIDTH)?.toInt(), lng(MediaStore.MediaColumns.HEIGHT)?.toInt(), if (kind == MediaKind.VIDEO) lng(MediaStore.Video.Media.DURATION) else null)
            }
        }.getOrNull() ?: Meta(id, null, if (kind == MediaKind.PHOTO) "image/jpeg" else "video/mp4", null, null, null, null)
    }

    /**
     * Writes GPS (if CameraX could not) and the time-zone offset into the app's own JPEG, which CameraX
     * just created (never a user's file). Preferred path: in place through a read-write descriptor.
     * Fallback (providers without seekable descriptors): copy to a temp, edit, verify the temp still
     * parses, then write it back. Failures are logged, never fatal: the row carries the position anyway.
     */
    private fun stampExif(uri: Uri, location: Location?, takenUtcMs: Long, tzId: String) {
        val inPlace = runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                stamp(exif, location, takenUtcMs, tzId)
                exif.saveAttributes()
            } ?: error("no descriptor")
        }
        if (inPlace.isSuccess) return
        val tmp = java.io.File(context.cacheDir, "camera-stamp-${System.nanoTime()}.jpg")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } ?: error("cannot read $uri")
            val exif = ExifInterface(tmp)
            stamp(exif, location, takenUtcMs, tzId)
            exif.saveAttributes()
            // The temp must be a whole, readable JPEG before the original is replaced (review I7).
            check(tmp.length() > 0 && ExifInterface(tmp).getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) != null) { "stamped copy unreadable" }
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } } ?: error("cannot write $uri")
        }.onFailure { Log.w(TAG, "exif stamp failed for $uri: ${it.javaClass.simpleName} ${it.message} (in place: ${inPlace.exceptionOrNull()?.javaClass?.simpleName})") }
        tmp.delete()
    }

    companion object {
        private const val TAG = "Bitacora"
        const val APP_FOLDER = "BitacoraViaje"

        /** A fix older than this is not "the place of the shot" (DISENO §7.6 uses the same 2 min for EXIF vs track). */
        const val MAX_FIX_AGE_MS = 120_000L

        /** Folder per trip under `Pictures/BitacoraViaje/`: the title without path-unsafe characters, or "Viaje". */
        fun folderName(tripTitle: String?): String =
            tripTitle?.trim()?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")?.take(60)?.trim()?.ifBlank { null } ?: "Viaje"

        private fun zone(tzId: String): ZoneId = runCatching { ZoneId.of(tzId) }.getOrDefault(ZoneId.of("UTC"))

        /** `+HH:MM` as EXIF wants it (`ZoneOffset.toString()` says "Z" for UTC). */
        fun offsetString(takenUtcMs: Long, tzId: String): String =
            zone(tzId).rules.getOffset(Instant.ofEpochMilli(takenUtcMs)).toString().let { if (it == "Z") "+00:00" else it }

        /**
         * Pure EXIF edit (testable on a file): the stage's offset, DateTimeOriginal only when the camera
         * did not write one (CameraX's is the real shutter instant, review I5), and the fix when absent.
         */
        fun stamp(exif: ExifInterface, location: Location?, takenUtcMs: Long, tzId: String) {
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(zone(tzId)).format(Instant.ofEpochMilli(takenUtcMs)))
            }
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetString(takenUtcMs, tzId))
            if (location != null && exif.latLong == null) exif.setGpsInfo(location)
        }

        /** The row for a shot taken by the app: exact instant and place → EXIF link with confidence 1.0; without a fix it waits for the sweep. */
        fun mediaItem(
            tripId: String, segmentId: String, kind: MediaKind, uri: Uri, mediaStoreId: Long?, displayName: String?, mimeType: String?,
            sizeBytes: Long?, width: Int?, height: Int?, durationMs: Long?, takenUtcMs: Long, lat: Double?, lon: Double?,
        ): MediaItem = MediaItem(
            id = UUID.randomUUID().toString(), tripId = tripId, segmentId = segmentId, kind = kind, source = MediaSource.APP_CAMERA,
            contentUri = uri.toString(), mediaStoreId = mediaStoreId, displayName = displayName, mimeType = mimeType, sizeBytes = sizeBytes,
            width = width, height = height, durationMs = durationMs, takenUtcMs = takenUtcMs,
            // The photo carries DateTimeOriginal + offset; the MP4 has no EXIF, its time is the recorder's (as MediaStore reports it).
            takenTimeSource = if (kind == MediaKind.PHOTO) TakenTimeSource.EXIF_WITH_OFFSET else TakenTimeSource.MEDIASTORE,
            exifLat = lat, exifLon = lon, cameraMake = Build.MANUFACTURER, cameraModel = Build.MODEL,
            linkedLat = lat, linkedLon = lon, linkMethod = if (lat != null && lon != null) LinkMethod.EXIF else LinkMethod.NONE,
            linkConfidence = if (lat != null && lon != null) 1.0 else 0.0, linkTimeGapMs = 0, needsReview = lat == null || lon == null,
        )
    }
}
