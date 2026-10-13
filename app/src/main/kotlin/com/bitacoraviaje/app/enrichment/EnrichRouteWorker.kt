package com.bitacoraviaje.app.enrichment

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `enrich-<segmentId>`, KEEP, network required, exponential backoff: the public OSM instances are often busy. */
@HiltWorker
class EnrichRouteWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val namer: RouteNamer,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val segmentId = inputData.getString(KEY_SEGMENT_ID) ?: return@withContext Result.failure()
        try {
            val o = namer.name(segmentId)
            Result.success(workDataOf(KEY_NAME to o?.name, KEY_SOURCE to o?.source))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "route naming failed segment=$segmentId attempt=$runAttemptCount: ${e.javaClass.simpleName} ${e.message}")
            // Only transient failures are retried; a parse problem will not fix itself (review M10).
            val transient = e is java.io.IOException
            if (transient && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "Bitacora"
        const val KEY_SEGMENT_ID = "segment_id"
        const val KEY_NAME = "name"
        const val KEY_SOURCE = "source"
        const val MAX_ATTEMPTS = 6

        fun enqueue(context: Context, segmentId: String) {
            val req = OneTimeWorkRequestBuilder<EnrichRouteWorker>()
                .setInputData(workDataOf(KEY_SEGMENT_ID to segmentId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("enrich-$segmentId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
