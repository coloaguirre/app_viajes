package com.bitacoraviaje.app.voice.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bitacoraviaje.app.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads a whisper model (and the VAD model) with resume (`Range`), progress and
 * SHA-256 verification (skill voice-journal step 7). Foreground `dataSync` with a
 * progress notification; unique per model so a second tap does not start twice.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val notifications = appContext.getSystemService(NotificationManager::class.java)
    private val store = ModelStore(File(appContext.filesDir, "models"))

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0L, 1L)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = WhisperModel.byId(inputData.getString(KEY_MODEL))
        runCatching { setForeground(foregroundInfo(0L, model.sizeBytes)) }
        store.ensureDir()
        try {
            if (!store.isVadInstalled()) download(WhisperModel.VAD_URL, store.vadPartFile(), store.vadFile(), WhisperModel.VAD_SHA256, WhisperModel.VAD_SIZE, model.sizeBytes)
            if (!store.isInstalled(model)) download(model.url, store.partFile(model), store.file(model), model.sha256, model.sizeBytes, model.sizeBytes)
            Log.i(TAG, "model ${model.id} ready (${store.file(model).length()} bytes)")
            Result.success(workDataOf(KEY_MODEL to model.id))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "model download failed ${model.id} attempt=$runAttemptCount: ${e.javaClass.simpleName} ${e.message}")
            if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private suspend fun download(url: String, part: File, target: File, sha256: String, size: Long, total: Long) {
        val have = if (part.isFile) part.length() else 0L
        if (have < size) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            val resume = code == 206
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
            RandomAccessFile(part, "rw").use { out ->
                if (resume) out.seek(have) else out.setLength(0)
                conn.inputStream.use { ins ->
                    val buf = ByteArray(1 shl 16)
                    var done = if (resume) have else 0L
                    var lastReport = 0L
                    while (true) {
                        if (isStopped) throw IllegalStateException("stopped")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 2 * 1024 * 1024) {
                            lastReport = done
                            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to size))
                            notifications.notify(notificationId(), notification(done, total))
                        }
                    }
                }
            }
        }
        if (part.length() != size) {
            part.delete()
            throw IllegalStateException("tamaño inesperado: ${part.length()} de $size bytes")
        }
        if (!ModelStore.commitVerified(part, target, sha256)) throw IllegalStateException("la verificación SHA-256 falló; descarga descartada")
    }

    private fun notificationId() = NOTIFICATION_ID_BASE + WhisperModel.byId(inputData.getString(KEY_MODEL)).ordinal

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, appContext.getString(R.string.model_notif_channel), NotificationManager.IMPORTANCE_LOW))
        val n = notification(done, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(notificationId(), n)
    }

    private fun notification(done: Long, total: Long) = NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(appContext.getString(R.string.model_notif_title))
        .setContentText(appContext.getString(R.string.model_notif_progress, done / 1_048_576, total / 1_048_576))
        .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val TAG = "Bitacora"
        const val CHANNEL_ID = "models"
        const val NOTIFICATION_ID_BASE = 2002
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        fun workName(model: WhisperModel) = "model-${model.id}"

        fun enqueue(context: Context, model: WhisperModel, allowMetered: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL to model.id))
                // Hundreds of MB: Wi-Fi only unless the user opts into mobile data (review M13).
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, model: WhisperModel) = WorkManager.getInstance(context).cancelUniqueWork(workName(model))
    }
}
