package com.bitacoraviaje.app.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bitacoraviaje.app.R
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `render-<segmentId>`: plans the 1-minute video, draws the cards, runs Media3 Transformer in
 * the foreground (dataSync type; `mediaProcessing` and its time limit are a Fase 5 check) and
 * saves the MP4 under Movies/BitacoraViaje through MediaStore (PLAN 4.6, DISENO §13.6).
 */
@UnstableApi
@HiltWorker
class RenderWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val planner: MiniVideoPlanner,
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    override suspend fun doWork(): Result {
        val segmentId = inputData.getString(KEY_SEGMENT_ID) ?: return Result.failure(workDataOf(KEY_ERROR to "sin etapa"))
        val started = System.currentTimeMillis()
        runCatching { setForeground(foregroundInfo(0)) }.onFailure { Log.w(TAG, "foreground not allowed: ${it.message}") }
        val work = File(appContext.cacheDir, "render/$segmentId").apply { deleteRecursively(); mkdirs() }
        return try {
            val prepared = withContext(Dispatchers.IO) { planner.prepare(segmentId, inputData.getString(KEY_MUSIC)) } ?: return Result.failure(workDataOf(KEY_ERROR to "etapa inexistente"))
            val segment = tracking.getSegment(segmentId)!!
            val points = withContext(Dispatchers.IO) { tracking.validPoints(segmentId) }
            val mediaById = withContext(Dispatchers.IO) { media.allOfTrip(segment.tripId).associateBy { it.id } }
            val voices = withContext(Dispatchers.IO) { notes.voiceNotesOf(segmentId).associateBy { it.id } }
            val base = RouteSnapshotter(appContext).snapshot(points, WIDTH, HEIGHT)
            val built = withContext(Dispatchers.Default) { MiniVideoComposer(appContext, work, WIDTH, HEIGHT).build(prepared.plan, prepared.input, mediaById, voices, points, base) }
            val out = File(work, "resumen.mp4")
            export(built.composition, out)
            val uri = withContext(Dispatchers.IO) { publish(out, segment.displayName ?: "etapa") } ?: Uri.fromFile(out)
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "mini video rendered segment=$segmentId duration=${built.durationMs}ms elapsed=${elapsed}ms skipped=${built.skipped.size}")
            Result.success(workDataOf(KEY_RESULT_URI to uri.toString(), KEY_DURATION_MS to built.durationMs, KEY_ELAPSED_MS to elapsed, KEY_SKIPPED to built.skipped.size))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "mini video failed segment=$segmentId: ${e.javaClass.simpleName} ${e.message}")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            work.listFiles()?.filter { it.name != "resumen.mp4" }?.forEach { it.delete() }
        }
    }

    /** Transformer wants the main looper; progress is polled every 500 ms into setProgress and the notification. */
    private suspend fun export(composition: Composition, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val transformer = Transformer.Builder(appContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resumeWithException(exportException) }
                })
                .build()
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        setProgressAsync(workDataOf(KEY_PROGRESS to holder.progress))
                        runCatching { setForegroundAsync(foregroundInfo(holder.progress)) }
                    }
                    handler.postDelayed(this, 500)
                }
            }
            cont.invokeOnCancellation { handler.removeCallbacks(poll); transformer.cancel() }
            transformer.start(composition, out.absolutePath)
            handler.post(poll)
        }
    }

    /** Movies/BitacoraViaje/<name>_<fecha>.mp4 via MediaStore (API 29+); older devices keep the cache file. */
    private fun publish(file: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "etapa" }.take(40)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${safe}_$stamp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/BitacoraViaje")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun foregroundInfo(progress: Int): ForegroundInfo {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, appContext.getString(R.string.render_channel), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(appContext.getString(R.string.render_notif_title))
            .setContentText(appContext.getString(R.string.render_notif_progress, progress))
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "Bitacora"
        const val KEY_SEGMENT_ID = "segment_id"
        const val KEY_MUSIC = "music"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_ELAPSED_MS = "elapsed_ms"
        const val KEY_SKIPPED = "skipped"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "render"
        const val NOTIFICATION_ID = 4206
        const val WIDTH = 1920
        const val HEIGHT = 1080

        fun workName(segmentId: String) = "render-$segmentId"

        fun enqueue(context: Context, segmentId: String, musicFile: String? = null) {
            val req = OneTimeWorkRequestBuilder<RenderWorker>().setInputData(workDataOf(KEY_SEGMENT_ID to segmentId, KEY_MUSIC to musicFile)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(segmentId), ExistingWorkPolicy.KEEP, req)
        }
    }
}
