package com.bitacoraviaje.app.home

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bitacoraviaje.app.export.BackupFiles
import com.bitacoraviaje.app.export.ExportLoader
import com.bitacoraviaje.app.export.ExportWorker
import com.bitacoraviaje.app.video.VideoPlanStore
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backlog D1: removes a trip the user confirmed, never before its backup exists.
 *
 * Order: (1) refuse if a segment of the trip is recording; (2) rewrite `files/backup/<id>.json.gz`
 * — if that fails nothing else happens and the trip stays; (3) move the voice recordings to
 * `files/backup/audio/<id>/` next to the backup (they are not inside the json, so they are kept,
 * not deleted); (4) delete the rows in one transaction (foreign keys cascade, orphan places go);
 * (5) drop the saved video plan and the export staging folder. Gallery photos and videos are never
 * touched (CLAUDE.md: originals are read-only).
 */
class TripDeleter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackingRepository,
    private val loader: ExportLoader,
    private val videoPlans: VideoPlanStore,
) {
    enum class Outcome { DELETED, IN_USE, BACKUP_FAILED, NOT_FOUND }

    suspend fun delete(tripId: String, backup: suspend (String) -> File? = { BackupFiles.write(context, loader, it) }): Outcome {
        if (!repository.tripExists(tripId)) return Outcome.NOT_FOUND
        if (repository.getOpenSegment()?.tripId == tripId) return Outcome.IN_USE
        val written = try {
            backup(tripId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "backup before delete failed trip=$tripId: ${e.javaClass.simpleName} ${e.message}")
            null
        }
        if (written == null || !written.isFile || written.length() == 0L) return Outcome.BACKUP_FAILED
        val audio = audioDir(context, tripId)
        if (audio.isDirectory) {
            val kept = audioTrashDir(context, tripId)
            kept.parentFile?.mkdirs()
            if (kept.exists()) kept.deleteRecursively()
            if (!audio.renameTo(kept)) {
                // Different mount or a leftover: copy then remove, so no recording is lost.
                audio.copyRecursively(kept, overwrite = true)
                audio.deleteRecursively()
            }
        }
        if (!repository.deleteTrip(tripId)) return Outcome.IN_USE
        runCatching { videoPlans.clear(tripId) }
        runCatching { ExportWorker.stagingDir(context, tripId).deleteRecursively() }
        Log.i(TAG, "trip deleted id=$tripId backup=${written.name}")
        return Outcome.DELETED
    }

    companion object {
        const val TAG = "Bitacora"
        fun audioDir(context: Context, tripId: String) = File(context.filesDir, "audio/${com.bitacoraviaje.core.export.SafeId.require(tripId, "trip id")}")
        fun audioTrashDir(context: Context, tripId: String) = File(BackupFiles.dir(context), "audio/${com.bitacoraviaje.core.export.SafeId.require(tripId, "trip id")}")
    }
}

/** Runs [TripDeleter] off the UI, surviving the screen and the process; one unique job per trip. */
@HiltWorker
class DeleteTripWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val deleter: TripDeleter,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return@withContext Result.failure()
        val outcome = try {
            deleter.delete(tripId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TripDeleter.TAG, "delete failed trip=$tripId: ${e.javaClass.simpleName} ${e.message}")
            TripDeleter.Outcome.BACKUP_FAILED
        }
        val out = workDataOf(KEY_TRIP_ID to tripId, KEY_OUTCOME to outcome.name)
        if (outcome == TripDeleter.Outcome.DELETED || outcome == TripDeleter.Outcome.NOT_FOUND) Result.success(out) else Result.failure(out)
    }

    companion object {
        const val KEY_TRIP_ID = "trip_id"
        const val KEY_OUTCOME = "outcome"
        const val TAG_WORK = "delete-trip"
        fun workName(tripId: String) = "delete-trip-$tripId"

        fun enqueue(context: Context, tripId: String) {
            val req = OneTimeWorkRequestBuilder<DeleteTripWorker>()
                .setInputData(workDataOf(KEY_TRIP_ID to tripId))
                .addTag(TAG_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(tripId), ExistingWorkPolicy.KEEP, req)
        }
    }
}
