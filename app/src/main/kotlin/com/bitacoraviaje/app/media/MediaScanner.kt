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
) {
    companion object {
        /** Pure mapping from an ExifInterface; (0,0) means the system redacted the location. */
        fun from(exif: ExifInterface): ExifData {
            val ll = exif.latLong?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }
            return ExifData(
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                subsecOriginal = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                gpsDateStamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP),
                gpsTimeStamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP),
                lat = ll?.get(0), lon = ll?.get(1),
                make = exif.getAttribute(ExifInterface.TAG_MAKE), model = exif.getAttribute(ExifInterface.TAG_MODEL),
            )
        }
    }
}

/** What the linker needs from the device's media catalog; MediaStore in production, a fake in tests. */
interface MediaCatalog {
    suspend fun queryByTime(fromUtcMs: Long, toUtcMs: Long): List<MediaCandidate>
}

/**
 * MediaStore access (DISENO §7.3). Queries by time window; excludes screenshots,
 * messaging folders and downloads by path. The live observer triggers a windowed
 * rescan of the open segment (the generation-based incremental query is deferred,
 * see PLAN 2.2).
 */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: MediaPermissions,
) : MediaCatalog {
    private val resolver: ContentResolver get() = context.contentResolver

    val excludedPathPrefixes: List<String> = listOf("Pictures/Screenshots", "WhatsApp", "Download", "Telegram", "Android/media")

    /** Images and videos whose capture time falls inside [fromUtcMs, toUtcMs]. */
    override suspend fun queryByTime(fromUtcMs: Long, toUtcMs: Long): List<MediaCandidate> = withContext(Dispatchers.IO) {
        val sel = "(${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ?) OR " +
            "((${MediaStore.MediaColumns.DATE_TAKEN} IS NULL OR ${MediaStore.MediaColumns.DATE_TAKEN} = 0) AND ${MediaStore.MediaColumns.DATE_ADDED} BETWEEN ? AND ?)"
        val args = arrayOf("$fromUtcMs", "$toUtcMs", "${fromUtcMs / 1000}", "${toUtcMs / 1000}")
        query(MediaKind.PHOTO, sel, args) + query(MediaKind.VIDEO, sel, args)
    }

    private fun query(kind: MediaKind, selection: String, args: Array<String>): List<MediaCandidate> {
        if (permissions.access() == MediaAccess.NONE) return emptyList()
        val uri = when {
            kind == MediaKind.PHOTO && Build.VERSION.SDK_INT >= 29 -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            kind == MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= 29 -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        // RELATIVE_PATH, IS_PENDING and ORIENTATION-for-videos exist from API 29; on 26–28 the
        // provider rejects unknown columns, so the projection is built per API level (review I4).
        val api29 = Build.VERSION.SDK_INT >= 29
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
        )
        if (api29) {
            proj += listOf(MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING)
        } else {
            @Suppress("DEPRECATION")
            proj += MediaStore.MediaColumns.DATA
            if (kind == MediaKind.PHOTO) proj += COLUMN_ORIENTATION
        }
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
            val iOri = c.getColumnIndex(COLUMN_ORIENTATION)
            @Suppress("DEPRECATION")
            val iPath = if (api29) c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH) else c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val iPending = if (api29) c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING) else -1
            val iDur = if (kind == MediaKind.VIDEO) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
            while (c.moveToNext()) {
                if (iPending >= 0 && c.getInt(iPending) == 1) continue // still being written (review G5): the next scan picks it up
                val path = c.getString(iPath)
                if (path != null && isExcludedPath(path, api29)) continue
                val id = c.getLong(iId)
                val item = ContentUris.withAppendedId(uri, id)
                out += MediaCandidate(
                    mediaStoreId = id, contentUri = item, kind = kind,
                    displayName = c.getString(iName), mimeType = c.getString(iMime),
                    sizeBytes = if (c.isNull(iSize)) null else c.getLong(iSize),
                    width = if (c.isNull(iW)) null else c.getInt(iW), height = if (c.isNull(iH)) null else c.getInt(iH),
                    durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else null,
                    orientationDeg = if (iOri < 0 || c.isNull(iOri)) null else c.getInt(iOri),
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

    /** Relative path ("Pictures/Screenshots/x.jpg") on API 29+; absolute `_data` before that. */
    internal fun isExcludedPath(path: String, relative: Boolean): Boolean =
        excludedPathPrefixes.any { if (relative) path.startsWith(it, ignoreCase = true) else path.contains("/$it", ignoreCase = true) }

    /** EXIF with the original (un-redacted) stream when ACCESS_MEDIA_LOCATION is granted (DISENO §7.4). */
    private fun readExif(contentUri: Uri): ExifData? {
        val uri = if (Build.VERSION.SDK_INT >= 29 && permissions.hasMediaLocation()) MediaStore.setRequireOriginal(contentUri) else contentUri
        return try {
            resolver.openInputStream(uri)?.use { ins -> ExifData.from(ExifInterface(ins)) }
        } catch (e: SecurityException) {
            // Without ACCESS_MEDIA_LOCATION the original stream is refused: retry redacted (no GPS).
            if (uri == contentUri) return null
            Log.w(TAG, "original stream refused for $contentUri (no ACCESS_MEDIA_LOCATION?): reading redacted EXIF")
            runCatching { resolver.openInputStream(contentUri)?.use { ExifData.from(ExifInterface(it)).copy(lat = null, lon = null) } }.getOrNull()
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

    companion object {
        private const val TAG = "Bitacora"

        /** Same column name on every API level; typed constants differ (Images.Media vs MediaColumns). */
        const val COLUMN_ORIENTATION = "orientation"
    }
}
