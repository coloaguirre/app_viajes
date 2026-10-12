package com.bitacoraviaje.app.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.bitacoraviaje.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A MediaStore row plus whatever metadata we could read, before linking. */
data class MediaCandidate(
    val mediaStoreId: Long,
    val contentUri: Uri,
    val kind: MediaKind,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val orientationDeg: Int?,
    val dateTakenMs: Long?,
    val dateAddedS: Long?,
    val dateModifiedS: Long?,
    val relativePath: String?,
    val exif: ExifData?,
)

data class ExifData(
    val dateTimeOriginal: String?,
    val offsetTimeOriginal: String?,
    val subsecOriginal: String?,
    val gpsDateStamp: String?,
    val gpsTimeStamp: String?,
    val lat: Double?,
    val lon: Double?,
    val make: String?,
    val model: String?,
)

/**
 * MediaStore access (DISENO §7.3). Queries by time window and by generation (API 30+)
 * so the live observer only looks at what changed; excludes screenshots, messaging
 * folders and downloads by relative path.
 */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: MediaPermissions,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    var excludedPathPrefixes: List<String> = listOf("Pictures/Screenshots", "WhatsApp", "Download", "Telegram", "Android/media")

    /** Images and videos whose capture time falls inside [fromUtcMs, toUtcMs]. */
    suspend fun queryByTime(fromUtcMs: Long, toUtcMs: Long): List<MediaCandidate> = withContext(Dispatchers.IO) {
        val sel = "(${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ?) OR " +
            "((${MediaStore.MediaColumns.DATE_TAKEN} IS NULL OR ${MediaStore.MediaColumns.DATE_TAKEN} = 0) AND ${MediaStore.MediaColumns.DATE_ADDED} BETWEEN ? AND ?)"
        val args = arrayOf("$fromUtcMs", "$toUtcMs", "${fromUtcMs / 1000}", "${toUtcMs / 1000}")
        query(MediaKind.PHOTO, sel, args) + query(MediaKind.VIDEO, sel, args)
    }

    /** Rows added since [generation] (MediaStore.getGeneration, API 30+); null → everything recent. */
    suspend fun queryAddedSince(generation: Long): List<MediaCandidate> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext emptyList()
        val sel = "${MediaStore.MediaColumns.GENERATION_ADDED} > ?"
        val args = arrayOf("$generation")
        query(MediaKind.PHOTO, sel, args) + query(MediaKind.VIDEO, sel, args)
    }

    fun currentGeneration(): Long =
        if (Build.VERSION.SDK_INT >= 30) runCatching { MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY) }.getOrDefault(0L) else 0L

    private fun query(kind: MediaKind, selection: String, args: Array<String>): List<MediaCandidate> {
        if (permissions.access() == MediaAccess.NONE) return emptyList()
        val uri = when {
            kind == MediaKind.PHOTO && Build.VERSION.SDK_INT >= 29 -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            kind == MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= 29 -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING,
        )
        if (kind == MediaKind.VIDEO) proj += MediaStore.MediaColumns.DURATION
        val out = ArrayList<MediaCandidate>()
        resolver.query(uri, proj.toTypedArray(), selection, args, "${MediaStore.MediaColumns.DATE_TAKEN} ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iOri = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
            val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iPending = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            val iDur = if (kind == MediaKind.VIDEO) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
            while (c.moveToNext()) {
                if (c.getInt(iPending) == 1) continue // still being written (review G5): the next scan picks it up
                val path = c.getString(iPath)
                if (path != null && excludedPathPrefixes.any { path.startsWith(it, ignoreCase = true) }) continue
                val id = c.getLong(iId)
                val item = ContentUris.withAppendedId(uri, id)
                out += MediaCandidate(
                    mediaStoreId = id, contentUri = item, kind = kind,
                    displayName = c.getString(iName), mimeType = c.getString(iMime),
                    sizeBytes = if (c.isNull(iSize)) null else c.getLong(iSize),
                    width = if (c.isNull(iW)) null else c.getInt(iW), height = if (c.isNull(iH)) null else c.getInt(iH),
                    durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else null,
                    orientationDeg = if (c.isNull(iOri)) null else c.getInt(iOri),
                    dateTakenMs = if (c.isNull(iTaken)) null else c.getLong(iTaken).takeIf { it > 0 },
                    dateAddedS = if (c.isNull(iAdded)) null else c.getLong(iAdded),
                    dateModifiedS = if (c.isNull(iMod)) null else c.getLong(iMod),
                    relativePath = path,
                    exif = if (kind == MediaKind.PHOTO) readExif(item) else readVideoMeta(item),
                )
            }
        }
        return out
    }

    /** EXIF with the original (un-redacted) stream when ACCESS_MEDIA_LOCATION is granted (DISENO §7.4). */
    private fun readExif(contentUri: Uri): ExifData? {
        val uri = if (Build.VERSION.SDK_INT >= 29 && permissions.hasMediaLocation()) MediaStore.setRequireOriginal(contentUri) else contentUri
        return try {
            resolver.openInputStream(uri)?.use { ins ->
                val exif = ExifInterface(ins)
                val ll = exif.latLong?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }
                ExifData(
                    dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                    subsecOriginal = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                    gpsDateStamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP),
                    gpsTimeStamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP),
                    lat = ll?.get(0), lon = ll?.get(1),
                    make = exif.getAttribute(ExifInterface.TAG_MAKE), model = exif.getAttribute(ExifInterface.TAG_MODEL),
                )
            }
        } catch (e: SecurityException) {
            // Without ACCESS_MEDIA_LOCATION the original stream is refused: retry redacted (no GPS).
            if (uri == contentUri) return null
            runCatching { resolver.openInputStream(contentUri)?.use { ExifInterface(it) } }.getOrNull()?.let { exif ->
                ExifData(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL), null, null, null, null, exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
            }
        } catch (e: Exception) {
            Log.w(TAG, "exif read failed for $contentUri: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Video metadata: ISO-6709 location and the creation date. MP4 `creation_time` is the
     * END of the recording (review R7); the linker subtracts the duration.
     */
    private fun readVideoMeta(contentUri: Uri): ExifData? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, contentUri)
            val loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val m = loc?.let { Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(it) }
            val date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) // yyyyMMdd'T'HHmmss.SSS'Z'
            ExifData(
                dateTimeOriginal = date?.let { videoDateToExif(it) }, offsetTimeOriginal = if (date != null) "+00:00" else null, subsecOriginal = null,
                gpsDateStamp = null, gpsTimeStamp = null,
                lat = m?.groupValues?.get(1)?.toDoubleOrNull(), lon = m?.groupValues?.get(2)?.toDoubleOrNull(),
                make = null, model = null,
            )
        } finally {
            r.release() // AutoCloseable only from API 29
        }
    } catch (e: Exception) {
        null
    }

    private fun videoDateToExif(d: String): String? {
        // 20261012T185132.000Z -> 2026:10:12 18:51:32
        val m = Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""").find(d) ?: return null
        val (y, mo, da, h, mi, s) = m.destructured
        return "$y:$mo:$da $h:$mi:$s"
    }

    private companion object {
        const val TAG = "Bitacora"
    }
}
