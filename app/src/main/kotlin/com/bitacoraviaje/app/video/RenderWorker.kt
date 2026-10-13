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
import com.bitacoraviaje.core.videoplan.VideoPlan
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Video rendering in the foreground (PLAN 4.6 and 5.1, DISENO §13.6): the 1-minute stage video
 * (`render-<segmentId>`) or the full trip video from the plan the user reviewed
 * (`render-trip-<tripId>`). Draws the cards, runs Media3 Transformer, measures the result and
 * saves the MP4 under Movies/BitacoraViaje through MediaStore. Foreground type `dataSync`:
 * `mediaProcessing` (Android 15) and its 6-hour budget are evaluated in Fase 6 before release.
 */
@UnstableApi
@HiltWorker
class RenderWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val planner: MiniVideoPlanner,
    private val tripPlanner: TripVideoPlanner,
    private val store: VideoPlanStore,
    private val tracking: TrackingRepository,
    private val media: MediaRepository,
    private val notes: NotesRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    override suspend fun doWork(): Result {
        val segmentId = inputData.getString(KEY_SEGMENT_ID)
        val tripId = inputData.getString(KEY_TRIP_ID)
        if (segmentId == null && tripId == null) return Result.failure(workDataOf(KEY_ERROR to appContext.getString(R.string.video_error_no_segment)))
        val started = System.currentTimeMillis()
        runCatching { setForeground(foregroundInfo(0)) }.onFailure { Log.w(TAG, "foreground not allowed: ${it.message}") }
        // One directory per run (review I2): a replaced run may still be writing while the new one starts.
        val renderRoot = File(appContext.cacheDir, "render")
        renderRoot.listFiles()?.filter { it.isDirectory && System.currentTimeMillis() - it.lastModified() > STALE_MS }?.forEach { it.deleteRecursively() }
        val work = File(renderRoot, id.toString()).apply { mkdirs() }
        return try {
            val built: MiniVideoComposer.Built
            val name: String
            if (segmentId != null) {
                // PLAN 4.6: the 1-minute stage video, static map.
                val prepared = withContext(Dispatchers.IO) { planner.prepare(segmentId) } ?: return Result.failure(workDataOf(KEY_ERROR to appContext.getString(R.string.video_error_no_segment)))
                val segment = prepared.segment
                val points = withContext(Dispatchers.IO) { tracking.validPoints(segmentId) }
                val mediaById = withContext(Dispatchers.IO) { media.allOfTrip(segment.tripId).associateBy { it.id } }
                val voices = withContext(Dispatchers.IO) { notes.voiceNotesOf(segmentId).associateBy { it.id } }
                val base = RouteSnapshotter(appContext).snapshot(points, WIDTH, HEIGHT)
                built = withContext(Dispatchers.Default) { MiniVideoComposer(appContext, work, WIDTH, HEIGHT).build(prepared.plan, prepared.input, mediaById, voices, mapOf(segmentId to MiniVideoComposer.SegmentMedia(points, base)), isActive = { isActive }) }
                name = segment.displayName ?: appContext.getString(R.string.video_default_name)
            } else {
                // PLAN 5.1: the full trip video from the plan the user reviewed (VideoPlanStore), animated map, voices and music.
                val loaded = store.load(tripId!!)
                val plan = loaded?.plan ?: return Result.failure(workDataOf(KEY_ERROR to appContext.getString(R.string.video_error_no_plan)))
                val params = loaded.params
                val prepared = withContext(Dispatchers.IO) { tripPlanner.input(tripId) } ?: return Result.failure(workDataOf(KEY_ERROR to appContext.getString(R.string.video_error_no_segment)))
                val w = plan.output.width; val h = plan.output.height
                val mediaById = withContext(Dispatchers.IO) { media.allOfTrip(tripId).associateBy { it.id } }
                val voices = withContext(Dispatchers.IO) { prepared.segments.flatMap { notes.voiceNotesOf(it.id) }.associateBy { it.id } }
                val snapshotter = RouteSnapshotter(appContext)
                val perSegment = HashMap<String, MiniVideoComposer.SegmentMedia>()
                for (s in prepared.segments) {
                    val points = withContext(Dispatchers.IO) { tracking.validPoints(s.id) }
                    // The snapshot is fetched when a map scene asks for it and recycled by the composer (review I3).
                    perSegment[s.id] = MiniVideoComposer.SegmentMedia(points) { kotlinx.coroutines.runBlocking { snapshotter.snapshot(points, w, h) } }
                }
                val options = MiniVideoComposer.Options(
                    voice = params.toPlanParams().voice,
                    animateMap = true,
                    musicUri = params.musicUri?.let { Uri.parse(it) },
                    musicVolume = params.musicVolume.toFloat(),
                )
                built = withContext(Dispatchers.Default) { MiniVideoComposer(appContext, work, w, h, options = options).build(plan, prepared.input, mediaById, voices, perSegment, isActive = { isActive }) }
                name = prepared.input.title
            }
            val out = File(work, "resumen.mp4")
            export(built.composition, out)
            // What the user is told is measured on the file, never the plan's sum (review I8).
            val actualMs = withContext(Dispatchers.IO) { measuredDurationMs(out) ?: built.durationMs }
            val published = withContext(Dispatchers.IO) { publish(out, name) }
            if (published != null) out.delete() // the MediaStore copy is the one that counts (review M2)
            val uri = published ?: Uri.fromFile(out)
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "video rendered ${segmentId?.let { "segment=$it" } ?: "trip=$tripId"} planned=${built.durationMs}ms actual=${actualMs}ms elapsed=${elapsed}ms skipped=${built.skipped.size} voicesDropped=${built.voicesDropped} audio=${built.hasAudio} warnings=${built.warnings.size}")
            Result.success(workDataOf(KEY_RESULT_URI to uri.toString(), KEY_DURATION_MS to actualMs, KEY_PLANNED_MS to built.durationMs, KEY_ELAPSED_MS to elapsed, KEY_SKIPPED to built.skipped.size, KEY_VOICES_DROPPED to built.voicesDropped, KEY_WARNINGS to built.warnings.joinToString(" · ")))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Only the class and an error code reach the log: messages may carry the user's file names (review N5).
            val code = (e as? ExportException)?.errorCode
            Log.w(TAG, "video failed ${segmentId ?: tripId}: ${e.javaClass.simpleName} code=$code")
            Result.failure(workDataOf(KEY_ERROR to (if (code != null) "${e.javaClass.simpleName} $code" else e.message ?: e.javaClass.simpleName)))
        } finally {
            work.listFiles()?.filter { it.name != "resumen.mp4" }?.forEach { it.delete() }
        }
    }

    /** Duration of the produced MP4 as the container declares it. */
    private fun measuredDurationMs(file: File): Long? = runCatching {
        val r = android.media.MediaMetadataRetriever() // AutoCloseable only from API 29: release by hand (minSdk 26)
        try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    }.getOrNull()

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
            // Transformer must be cancelled on its application thread; WorkManager cancels from its own (review I3).
            cont.invokeOnCancellation { handler.post { handler.removeCallbacks(poll); runCatching { transformer.cancel() } } }
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
            val stream = resolver.openOutputStream(uri) ?: throw java.io.IOException("MediaStore sin flujo de salida")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // no pending row left behind (review M3)
            null
        }
    }

    private var channelReady = false

    private fun foregroundInfo(progress: Int): ForegroundInfo {
        if (!channelReady) {
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, appContext.getString(R.string.render_channel), NotificationManager.IMPORTANCE_LOW))
            channelReady = true
        }
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
        const val KEY_TRIP_ID = "trip_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_ELAPSED_MS = "elapsed_ms"
        const val KEY_SKIPPED = "skipped"
        const val KEY_PLANNED_MS = "planned_ms"
        const val KEY_VOICES_DROPPED = "voices_dropped"
        const val KEY_WARNINGS = "warnings"
        const val STALE_MS = 6 * 3_600_000L
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "render"
        const val NOTIFICATION_ID = 4206
        const val WIDTH = 1920
        const val HEIGHT = 1080

        fun workName(segmentId: String) = "render-$segmentId"

        fun tripWorkName(tripId: String) = "render-trip-$tripId"

        fun enqueue(context: Context, segmentId: String) {
            val req = OneTimeWorkRequestBuilder<RenderWorker>().setInputData(workDataOf(KEY_SEGMENT_ID to segmentId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(segmentId), ExistingWorkPolicy.KEEP, req)
        }

        /** Full trip video from the plan saved in [VideoPlanStore]; REPLACE so a re-render after editing the plan wins. */
        fun enqueueTrip(context: Context, tripId: String) {
            val req = OneTimeWorkRequestBuilder<RenderWorker>().setInputData(workDataOf(KEY_TRIP_ID to tripId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(tripWorkName(tripId), ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelTrip(context: Context, tripId: String) = WorkManager.getInstance(context).cancelUniqueWork(tripWorkName(tripId))
    }
}
