package com.bitacoraviaje.app.export

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bitacoraviaje.core.export.TripJsonWriter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes `files/backup/<tripId>.json` (the trip.json of DISENO §12.2) every time a
 * segment or the trip is closed (PLAN 3.5). That folder IS in the Auto Backup rules,
 * the database is not: this file is what a new phone restores through import.
 */
object BackupFiles {
    fun dir(context: Context): File = File(context.filesDir, "backup")

    fun fileFor(context: Context, tripId: String): File = File(dir(context), "$tripId.json")

    /** Atomic: written to a temp file, then renamed, so a crash never leaves a truncated backup. */
    suspend fun write(context: Context, loader: ExportLoader, tripId: String): File? {
        val trip = loader.load(tripId, ExportOptions(includeMedia = true, includeAudio = true, includeFilteredPoints = false)) ?: return null
        val target = fileFor(context, tripId)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { TripJsonWriter().write(trip, it) }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot replace ${target.name}" }
        }
        return target
    }
}

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val loader: ExportLoader,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return@withContext Result.failure()
        try {
            val f = BackupFiles.write(appContext, loader, tripId) ?: return@withContext Result.failure()
            Log.i(TAG, "backup written ${f.name} (${f.length() / 1024} KB)")
            Result.success(workDataOf(KEY_PATH to f.path))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "backup failed trip=$tripId: ${e.javaClass.simpleName} ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "Bitacora"
        const val KEY_TRIP_ID = "trip_id"
        const val KEY_PATH = "path"

        /** REPLACE: only the latest state of the trip matters. */
        fun enqueue(context: Context, tripId: String) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>().setInputData(workDataOf(KEY_TRIP_ID to tripId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("backup-$tripId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
