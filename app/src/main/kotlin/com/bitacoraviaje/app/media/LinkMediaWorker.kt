package com.bitacoraviaje.app.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background linking (DISENO §4.4 step 6). Unique per trip (`link-<tripId>`) with
 * APPEND_OR_REPLACE: a burst of observer events queues at most one more sweep.
 */
@HiltWorker
class LinkMediaWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val linker: MediaLinker,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return Result.failure()
        val segmentId = inputData.getString(KEY_SEGMENT_ID)
        return try {
            val r = linker.linkTrip(tripId, segmentId?.let { setOf(it) })
            Result.success(Data.Builder().putInt("scanned", r.scanned).putInt("linked", r.linked).putInt("review", r.review).build())
        } catch (e: Exception) {
            android.util.Log.w("Bitacora", "media link failed trip=$tripId attempt=$runAttemptCount: ${e.javaClass.simpleName} ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TRIP_ID = "trip_id"
        const val KEY_SEGMENT_ID = "segment_id"

        fun enqueue(context: Context, tripId: String, segmentId: String? = null) {
            val data = Data.Builder().putString(KEY_TRIP_ID, tripId).apply { if (segmentId != null) putString(KEY_SEGMENT_ID, segmentId) }.build()
            val req = OneTimeWorkRequestBuilder<LinkMediaWorker>().setInputData(data).build()
            WorkManager.getInstance(context).enqueueUniqueWork("link-$tripId", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
