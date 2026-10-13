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
import kotlinx.coroutines.Dispatchers
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

    /** The fix CameraX writes into the file's GPS tags (null when there is no recent position: the sweep then links by time). */
    suspend fun location(nowUtcMs: Long = System.currentTimeMillis()): Location? {
        val pos = positions.position(nowUtcMs)
        val lat = pos.lat ?: return null
        val lon = pos.lon ?: return null
        return Location("bitacora").apply {
            latitude = lat
            longitude = lon
            pos.accuracyM?.let { accuracy = it }
            time = nowUtcMs
        }
    }

    /** Registers a saved shot: EXIF offset stamp (photos), MediaStore metadata, and the linked row. */
    suspend fun register(uri: Uri, kind: MediaKind, segmentId: String, location: Location?, takenUtcMs: Long): MediaItem? = withContext(Dispatchers.IO) {
        val segment = tracking.getSegment(segmentId) ?: return@withContext null
        val meta = readMediaStore(uri, kind)
        if (kind == MediaKind.PHOTO) stampExif(uri, location, takenUtcMs, segment.tzId)
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
     * Writes GPS (if CameraX could not) and the time-zone offset into the app's own JPEG: the file is
     * copied to a temp, edited there and written back through the resolver (streams only, so it works
     * on every provider). Failures are logged, never fatal: the row still carries the position.
     */
    private fun stampExif(uri: Uri, location: Location?, takenUtcMs: Long, tzId: String) {
        val tmp = java.io.File(context.cacheDir, "camera-stamp-${System.nanoTime()}.jpg")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } ?: error("cannot read $uri")
            val exif = ExifInterface(tmp)
            stamp(exif, location, takenUtcMs, tzId)
            exif.saveAttributes()
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } } ?: error("cannot write $uri")
        }.onFailure { Log.w(TAG, "exif stamp failed for $uri: ${it.javaClass.simpleName} ${it.message}") }
        tmp.delete()
    }

    companion object {
        private const val TAG = "Bitacora"
        const val APP_FOLDER = "BitacoraViaje"

        /** Folder per trip under `Pictures/BitacoraViaje/`: the title without path-unsafe characters, or "Viaje". */
        fun folderName(tripTitle: String?): String =
            tripTitle?.trim()?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")?.take(60)?.trim()?.ifBlank { null } ?: "Viaje"

        private fun zone(tzId: String): ZoneId = runCatching { ZoneId.of(tzId) }.getOrDefault(ZoneId.of("UTC"))

        /** `+HH:MM` as EXIF wants it (`ZoneOffset.toString()` says "Z" for UTC). */
        fun offsetString(takenUtcMs: Long, tzId: String): String =
            zone(tzId).rules.getOffset(Instant.ofEpochMilli(takenUtcMs)).toString().let { if (it == "Z") "+00:00" else it }

        /** Pure EXIF edit (testable on a file): DateTimeOriginal in the stage's zone, its offset, and the GPS fix when present. */
        fun stamp(exif: ExifInterface, location: Location?, takenUtcMs: Long, tzId: String) {
            val local = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(zone(tzId)).format(Instant.ofEpochMilli(takenUtcMs))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, local)
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
            width = width, height = height, durationMs = durationMs, takenUtcMs = takenUtcMs, takenTimeSource = TakenTimeSource.EXIF_WITH_OFFSET,
            exifLat = lat, exifLon = lon, cameraMake = Build.MANUFACTURER, cameraModel = Build.MODEL,
            linkedLat = lat, linkedLon = lon, linkMethod = if (lat != null && lon != null) LinkMethod.EXIF else LinkMethod.NONE,
            linkConfidence = if (lat != null && lon != null) 1.0 else 0.0, linkTimeGapMs = 0, needsReview = lat == null || lon == null,
        )
    }
}
