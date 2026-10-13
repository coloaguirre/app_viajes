package com.bitacoraviaje.app.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Use-case façade for the UI: starts/stops the service and keeps the database consistent. */
@Singleton
class TrackingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackingRepository,
    private val stateHolder: TrackingStateHolder,
    private val notifications: TrackingNotificationFactory,
) {
    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Must be called while the app is visible (foreground service start rules). */
    suspend fun startSession(
        type: SegmentType,
        transport: Transport?,
        mode: RecordingMode,
        title: String? = null,
        intentText: String? = null,
        tripTitle: String? = null,
    ): String {
        check(hasLocationPermission()) { "location permission required" }
        // A new stage closes the open one: let the service persist its buffer first (I3).
        if (type == SegmentType.TRIP_START || type == SegmentType.STAGE) stopServiceAndAwaitFlush()
        val segment = repository.startSession(
            type, transport, mode, TimeZone.getDefault().id, title, intentText,
            tripTitle = tripTitle?.ifBlank { null } ?: context.getString(com.bitacoraviaje.app.R.string.default_trip_title),
        )
        startService(segment.id, segment.samplingProfile)
        return segment.id
    }

    private fun startService(segmentId: String, profile: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_SEGMENT_ID, segmentId)
                .putExtra(TrackingService.EXTRA_PROFILE, profile),
        )
    }

    /** Whether the recording service is alive in this process (false after a process death). */
    fun isServiceRunning(): Boolean = stateHolder.state.value.isRunning

    /**
     * Recovery (DISENO §6.9): an open segment in the database but no running service.
     * Restarts the service on that segment; a PAUSED segment stays paused.
     */
    fun resumeOpenSegment(segment: com.bitacoraviaje.core.model.Segment) {
        check(hasLocationPermission()) { "location permission required" }
        startService(segment.id, segment.samplingProfile)
    }

    /** Recovery dialog, "close": the segment keeps what was saved and is marked RECOVERED. */
    suspend fun closeOpenSegmentAsRecovered(segmentId: String) {
        val done = repository.finishSegment(segmentId, SegmentStatus.RECOVERED)
        notifications.cancelResumeNeeded()
        done?.let { com.bitacoraviaje.app.media.LinkMediaWorker.enqueue(context, it.tripId, it.id) }
    }

    fun pause() = send(TrackingService.ACTION_PAUSE)

    /** PLAN item 2.9: persist the change (segment + timeline note) and retune the running service. */
    suspend fun changeTransport(transport: Transport): Boolean {
        val live = stateHolder.state.value
        val id = live.segmentId ?: repository.getOpenSegment()?.id ?: return false
        val updated = repository.changeTransport(id, transport, live.lastPoint?.lat, live.lastPoint?.lon) ?: return false
        if (live.isRunning) {
            context.startService(
                Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_CHANGE_TRANSPORT)
                    .putExtra(TrackingService.EXTRA_TRANSPORT, transport.name)
                    .putExtra(TrackingService.EXTRA_PROFILE, updated.samplingProfile),
            )
        }
        return true
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }

    fun resume() = send(TrackingService.ACTION_RESUME)

    /**
     * Stops the service (which flushes its buffer) and closes the segment with stats.
     * A nested excursion/trek hands recording back to its paused parent (adenda 1:
     * the trip keeps going); returns the segment now recording, if any.
     */
    suspend fun finishSegment(segmentId: String): String? {
        stopServiceAndAwaitFlush()
        val done = repository.finishSegment(segmentId, SegmentStatus.FINISHED)
        stateHolder.reset()
        done?.let { com.bitacoraviaje.app.media.LinkMediaWorker.enqueue(context, it.tripId, it.id) }
        val parentId = done?.parentSegmentId ?: return null
        val parent = repository.getSegment(parentId)?.takeIf { it.status == SegmentStatus.PAUSED } ?: return null
        repository.resume(parent.id)
        startService(parent.id, parent.samplingProfile)
        return parent.id
    }

    suspend fun finishTrip(tripId: String) {
        stopServiceAndAwaitFlush()
        repository.finishTrip(tripId)
        stateHolder.reset()
        com.bitacoraviaje.app.media.LinkMediaWorker.enqueue(context, tripId)
    }

    suspend fun pauseInDb(segmentId: String, kind: PauseKind) = repository.pause(segmentId, kind)

    /**
     * The service flushes its point buffer asynchronously on STOP. Closing the segment
     * before that flush computed stats without the last buffered points (found in the
     * item 1.6 E2E: an excursion closed right after its fixes had 0 m). Wait for the
     * service to report it stopped, bounded so a dead service cannot block the UI.
     */
    private suspend fun stopServiceAndAwaitFlush() {
        if (!stateHolder.state.value.isRunning) return
        send(TrackingService.ACTION_STOP)
        withTimeoutOrNull(STOP_TIMEOUT_MS) { stateHolder.state.first { !it.isRunning } }
    }

    private fun send(action: String) {
        context.startService(Intent(context, TrackingService::class.java).setAction(action))
    }
}
