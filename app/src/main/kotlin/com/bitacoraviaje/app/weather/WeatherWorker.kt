package com.bitacoraviaje.app.weather

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

/** Backlog D3: fetches the hourly weather of a closed segment when online; same shape as EnrichPlacesWorker. */
@HiltWorker
class WeatherWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val enricher: WeatherEnricher,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val segmentId = inputData.getString(KEY_SEGMENT_ID) ?: return@withContext Result.failure()
        try {
            val force = inputData.getBoolean(KEY_FORCE, false) || runAttemptCount > 0
            val o = enricher.enrich(segmentId, force = force) ?: return@withContext Result.failure()
            val data = workDataOf(KEY_SAMPLES to o.samples, KEY_SKIPPED to o.skipped, KEY_PARTIAL to o.partial)
            if (o.partial && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success(data)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "weather failed segment=$segmentId attempt=$runAttemptCount: ${e.javaClass.simpleName} ${e.message}")
            if (e is java.io.IOException && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "Bitacora"
        const val KEY_SEGMENT_ID = "segment_id"
        const val KEY_FORCE = "force"
        const val KEY_SAMPLES = "samples"
        const val KEY_SKIPPED = "skipped"
        const val KEY_PARTIAL = "partial"
        const val TAG_WORK = "weather"
        const val MAX_ATTEMPTS = 3

        fun workName(segmentId: String) = "weather-$segmentId"

        fun enqueue(context: Context, segmentId: String, force: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<WeatherWorker>()
                .setInputData(workDataOf(KEY_SEGMENT_ID to segmentId, KEY_FORCE to force))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(TAG_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(segmentId), if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
