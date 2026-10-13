package com.bitacoraviaje.app.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bitacoraviaje.app.R
import com.bitacoraviaje.core.export.FileSource
import com.bitacoraviaje.core.export.StagedExport
import com.bitacoraviaje.core.export.TripExport
import com.bitacoraviaje.core.model.MediaKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the ZIP goes once built (DISENO §12.4). */
enum class ExportDestination {
    /** A document URI the user picked with the Storage Access Framework. */
    SAF,

    /** `Download/BitacoraViaje/` through MediaStore (API 29+), visible over USB. */
    DOWNLOADS,

    /** App cache, to be handed to the share sheet through the FileProvider. */
    SHARE,
}

/**
 * Builds the export package as a foreground `dataSync` job (DISENO §12.4): stages every
 * entry under the cache dir with a manifest checkpoint, so a retry after the process
 * died or the 6-hour daily `dataSync` budget (Android 15+) ran out resumes where it
 * stopped, then streams the ZIP to the destination. Progress goes to the notification
 * and to `setProgress` for the UI.
 */
@HiltWorker
class ExportWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val loader: ExportLoader,
) : CoroutineWorker(appContext, params) {
    private val notifications = appContext.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 1)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "sin viaje"))
        val options = ExportOptions(
            includeMedia = inputData.getBoolean(KEY_MEDIA, true),
            includeAudio = inputData.getBoolean(KEY_AUDIO, true),
            includeFilteredPoints = inputData.getBoolean(KEY_FILTERED, false),
        )
        val destination = ExportDestination.valueOf(inputData.getString(KEY_DESTINATION) ?: ExportDestination.SHARE.name)
        val targetUri = inputData.getString(KEY_TARGET_URI)?.toUri()
        runCatching { setForeground(foregroundInfo(0, 1)) }.onFailure { Log.w(TAG, "foreground not allowed: ${it.message}") }
        val trip = loader.load(tripId, options) ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "viaje inexistente"))
        val staging = stagingDir(appContext, tripId)
        // Staging + ZIP need roughly twice the media size; refuse early with a clear message (review I3, skill trip-export).
        val needed = estimateBytes(trip) * 2 + 20L * 1024 * 1024
        val free = android.os.StatFs(appContext.noBackupFilesDir.path).availableBytes
        if (free < needed) return@withContext Result.failure(workDataOf(KEY_ERROR to appContext.getString(com.bitacoraviaje.app.R.string.export_no_space, needed / 1_048_576, free / 1_048_576)))
        val staged = StagedExport(staging, thumbnails(trip), options.includeMedia, options.includeAudio)
        var createdUri: Uri? = null
        return@withContext try {
            val report = staged.run(trip, fileSource(trip)) { done, total -> publish(done, total) }
            val (uri, label) = openDestination(destination, targetUri, report.folder)
            createdUri = if (destination == ExportDestination.DOWNLOADS) uri else null
            // "wt": truncate. Some document providers keep the old tail with plain "w" and a retry would leave a corrupt ZIP (review I2).
            val out = uri?.let { appContext.contentResolver.openOutputStream(it, "wt") } ?: fileOutput(label!!)
            out.use { staged.zipTo(report, it) }
            if (destination == ExportDestination.DOWNLOADS && uri != null && Build.VERSION.SDK_INT >= 29) {
                appContext.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            staged.clear()
            Log.i(TAG, "export done trip=$tripId dest=$destination media=${report.mediaWritten}/${report.mediaInDb} audio=${report.audioWritten}/${report.voiceNotesInDb} missing=${report.mediaMissing.size + report.audioMissing.size}")
            Result.success(
                workDataOf(
                    KEY_RESULT_URI to (uri?.toString() ?: label), KEY_RESULT_FOLDER to report.folder, KEY_MEDIA_WRITTEN to report.mediaWritten, KEY_MEDIA_TOTAL to report.mediaInDb,
                    KEY_AUDIO_WRITTEN to report.audioWritten, KEY_AUDIO_TOTAL to report.voiceNotesInDb, KEY_MISSING to (report.mediaMissing.size + report.audioMissing.size),
                ),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "export failed trip=$tripId attempt=$runAttemptCount: ${e.javaClass.simpleName} ${e.message}")
            // A pending Downloads row of this attempt must not linger (review I2); staging is kept so the retry resumes.
            createdUri?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun estimateBytes(trip: TripExport): Long =
        trip.allMedia.filter { it.id in trip.mediaFiles }.sumOf { it.sizeBytes ?: 0L } + trip.allVoiceNotes.filter { it.id in trip.audioFiles }.sumOf { File(appContext.filesDir, it.audioPath).length() }

    private fun fileSource(trip: TripExport): FileSource {
        val byMediaPath = trip.allMedia.associateBy({ trip.mediaFiles[it.id] }, { it.contentUri })
        val byAudioPath = trip.allVoiceNotes.associateBy({ trip.audioFiles[it.id] }, { it.audioPath })
        return FileSource { path ->
            byMediaPath[path]?.let { uri -> runCatching { appContext.contentResolver.openInputStream(uri.toUri()) }.getOrNull() }
                ?: byAudioPath[path]?.let { rel -> File(appContext.filesDir, rel).takeIf { it.isFile }?.let(::FileInputStream) as InputStream? }
        }
    }

    /** 512 px JPEG thumbnails for the KMZ (Google Earth), API 29+; failures just leave the placemark without an image. */
    private fun thumbnails(trip: TripExport): Map<String, ByteArray> {
        if (Build.VERSION.SDK_INT < 29) return emptyMap()
        return trip.allMedia.filter { it.kind == MediaKind.PHOTO && it.contentUri != null && it.linkedLat != null }.take(MAX_THUMBNAILS).mapNotNull { m ->
            runCatching {
                val bmp = appContext.contentResolver.loadThumbnail(m.contentUri!!.toUri(), Size(512, 512), null)
                val bos = ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
                bmp.recycle()
                m.id to bos.toByteArray()
            }.getOrNull()
        }.toMap()
    }

    private fun openDestination(destination: ExportDestination, target: Uri?, folder: String): Pair<Uri?, String?> = when (destination) {
        ExportDestination.SAF -> requireNotNull(target) { "SAF destination without uri" } to null
        ExportDestination.DOWNLOADS -> if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$folder.zip")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BitacoraViaje")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads not available")
            uri to null
        } else {
            null to File(appContext.cacheDir, "export/$folder.zip").path // API 26–28: public Downloads need legacy storage permission; fall back to share
        }
        ExportDestination.SHARE -> null to File(appContext.cacheDir, "export/$folder.zip").path
    }

    private fun fileOutput(path: String): OutputStream {
        val f = File(path)
        f.parentFile?.mkdirs()
        return f.outputStream()
    }

    /** Called from the staging loop (not a coroutine): async progress + notification refresh. */
    private fun publish(done: Int, total: Int) {
        setProgressAsync(workDataOf(KEY_PROGRESS_DONE to done, KEY_PROGRESS_TOTAL to total))
        notifications.notify(NOTIFICATION_ID, notification(done, total))
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val n = notification(done, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private fun notification(done: Int, total: Int) = NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(appContext.getString(R.string.export_notif_title))
        .setContentText(appContext.resources.getQuantityString(R.plurals.export_notif_progress, total, done, total))
        .setProgress(total, done, total <= 1)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()

    private fun ensureChannel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, appContext.getString(R.string.export_notif_channel), NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val TAG = "Bitacora"
        const val CHANNEL_ID = "export"
        const val NOTIFICATION_ID = 2001
        const val MAX_THUMBNAILS = 300
        const val KEY_TRIP_ID = "trip_id"
        const val KEY_MEDIA = "media"
        const val KEY_AUDIO = "audio"
        const val KEY_FILTERED = "filtered"
        const val KEY_DESTINATION = "destination"
        const val KEY_TARGET_URI = "target_uri"
        const val KEY_PROGRESS_DONE = "done"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_RESULT_FOLDER = "result_folder"
        const val KEY_MEDIA_WRITTEN = "media_written"
        const val KEY_MEDIA_TOTAL = "media_total"
        const val KEY_AUDIO_WRITTEN = "audio_written"
        const val KEY_AUDIO_TOTAL = "audio_total"
        const val KEY_MISSING = "missing"
        const val KEY_ERROR = "error"

        fun workName(tripId: String) = "export-$tripId"

        /** Under noBackupFilesDir, not cache: the system may wipe the cache mid-export and break the resume (review I3). */
        fun stagingDir(context: Context, tripId: String): File = File(context.noBackupFilesDir, "export/staging/${com.bitacoraviaje.core.export.SafeId.require(tripId, "trip id")}")

        fun enqueue(context: Context, tripId: String, options: ExportOptions, destination: ExportDestination, targetUri: Uri? = null) {
            val data = Data.Builder()
                .putString(KEY_TRIP_ID, tripId)
                .putBoolean(KEY_MEDIA, options.includeMedia)
                .putBoolean(KEY_AUDIO, options.includeAudio)
                .putBoolean(KEY_FILTERED, options.includeFilteredPoints)
                .putString(KEY_DESTINATION, destination.name)
                .apply { targetUri?.let { putString(KEY_TARGET_URI, it.toString()) } }
                .build()
            val req = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // KEEP: a second tap while an export runs does not start a parallel one.
            WorkManager.getInstance(context).enqueueUniqueWork(workName(tripId), ExistingWorkPolicy.KEEP, req)
        }
    }
}
