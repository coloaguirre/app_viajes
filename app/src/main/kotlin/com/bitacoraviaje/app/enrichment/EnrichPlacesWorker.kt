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

/**
 * DISENO §11.6 `EnrichWorker`: `enrich-places-<segmentId>`, needs a connection, waits in the
 * queue offline. A total failure (no source answered) throws and is retried with exponential
 * backoff; a partial answer (one busy service) is kept and the run is retried too, forcing a
 * refresh over what was saved, up to [MAX_ATTEMPTS] (review I2). The Places screen
 * re-enqueues it with [KEY_FORCE] to refresh.
 */
@HiltWorker
class EnrichPlacesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val enricher: PlaceEnricher,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val segmentId = inputData.getString(KEY_SEGMENT_ID) ?: return@withContext Result.failure()
        try {
            // Retries after a partial run must look again instead of hitting the "already has places" gate.
            val force = inputData.getBoolean(KEY_FORCE, false) || runAttemptCount > 0
            val o = enricher.enrich(segmentId, force = force) ?: return@withContext Result.failure()
            val data = workDataOf(KEY_PLACES to o.placesFound, KEY_SKIPPED to o.skipped, KEY_PARTIAL to o.partial)
            if (o.partial && runAttemptCount < MAX_ATTEMPTS) {
                Log.i(TAG, "place enrichment partial segment=$segmentId attempt=$runAttemptCount: will retry")
                Result.retry()
            } else {
                Result.success(data)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "place enrichment failed segment=$segmentId attempt=$runAttemptCount: ${e.javaClass.simpleName} ${e.message}")
            if (e is java.io.IOException && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "Bitacora"
        const val KEY_SEGMENT_ID = "segment_id"
        const val KEY_FORCE = "force"
        const val KEY_PLACES = "places"
        const val KEY_SKIPPED = "skipped"
        const val KEY_PARTIAL = "partial"
        const val MAX_ATTEMPTS = 6
        const val TAG_WORK = "enrich-places"

        fun workName(segmentId: String) = "enrich-places-$segmentId"

        fun segmentTag(segmentId: String) = "segment:$segmentId"

        fun enqueue(context: Context, segmentId: String, force: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<EnrichPlacesWorker>()
                .setInputData(workDataOf(KEY_SEGMENT_ID to segmentId, KEY_FORCE to force))
                .addTag(segmentTag(segmentId)) // lets a trip filter the work it owns (review M2)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(TAG_WORK)
                .build()
            // KEEP after a segment closes; a user-requested refresh replaces whatever is queued.
            WorkManager.getInstance(context).enqueueUniqueWork(workName(segmentId), if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
