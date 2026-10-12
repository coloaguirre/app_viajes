package com.bitacoraviaje.app.tracking

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bitacoraviaje.core.geo.FilterConfig
import com.bitacoraviaje.core.geo.PointFilter
import com.bitacoraviaje.core.model.LocationPriority
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.SamplingProfile
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.data.repository.TrackingRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service of type `location` (DISENO §6.1). Started only from the visible
 * app; survives screen off; publishes live state through [TrackingStateHolder].
 * Points are buffered and flushed every [FLUSH_POINTS] fixes or [FLUSH_INTERVAL_MS].
 */
@AndroidEntryPoint
class TrackingService : LifecycleService() {
    @Inject lateinit var fused: FusedLocationProviderClient

    @Inject lateinit var repository: TrackingRepository

    @Inject lateinit var stateHolder: TrackingStateHolder

    @Inject lateinit var notifications: TrackingNotificationFactory

    private var segmentId: String? = null
    private var profile: SamplingProfile = SamplingProfile.WALK
    private var filter: PointFilter = PointFilter(FilterConfig.WALK)
    private val buffer = ArrayList<TrackPoint>()
    private val bufferLock = Mutex()
    private var lastFlushMs = 0L
    private var lastNotifyMs = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = segmentId ?: return
            val points = result.locations.map { it.toTrackPoint(id) }
            lifecycleScope.launch(Dispatchers.Default) { points.forEach { onPoint(it) } }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            stateHolder.update { it.copy(gpsAvailable = availability.isLocationAvailable) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(
                checkNotNull(intent.getStringExtra(EXTRA_SEGMENT_ID)),
                SamplingProfile.valueOf(checkNotNull(intent.getStringExtra(EXTRA_PROFILE))),
            )
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
            null -> recoverFromRestart()
        }
        return START_STICKY
    }

    private fun start(id: String, p: SamplingProfile) {
        segmentId = id
        profile = p
        filter = PointFilter(FilterConfig.forProfile(p))
        notifications.ensureChannel()
        val now = System.currentTimeMillis()
        stateHolder.update { LiveTrackingState(segmentId = id, isRunning = true, startedUtcMs = now, profile = p) }
        // minSdk 26: the typed constant exists from API 29; ServiceCompat ignores the type below that.
        @Suppress("InlinedApi")
        val fgsType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, TrackingNotificationFactory.NOTIFICATION_ID, notifications.build(stateHolder.state.value, now), fgsType)
        requestUpdates(p)
        Log.i(TAG, "tracking started segment=$id profile=$p")
    }

    /** START_STICKY restart after the system killed us: resume the open segment or quit. */
    private fun recoverFromRestart() {
        lifecycleScope.launch {
            val open = repository.getOpenSegment()
            if (open == null) {
                stopSelf()
                return@launch
            }
            val p = SamplingProfile.valueOf(open.samplingProfile)
            start(open.id, p)
            if (open.status == SegmentStatus.PAUSED) pause(record = false)
            Log.i(TAG, "recovered segment=${open.id} status=${open.status}")
        }
    }

    @SuppressLint("MissingPermission") // checked by TrackingController before starting
    private fun requestUpdates(p: SamplingProfile) {
        val priority = when (p.priority) {
            LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
            LocationPriority.BALANCED_POWER -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, p.intervalMs)
            .setMinUpdateIntervalMillis(p.minIntervalMs)
            .setMinUpdateDistanceMeters(p.minDistanceM)
            .setMaxUpdateDelayMillis(p.maxDelayMs)
            .setWaitForAccurateLocation(p == SamplingProfile.TREKKING_HIGH)
            .build()
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private suspend fun onPoint(raw: TrackPoint) {
        if (stateHolder.state.value.isPaused) return
        val verdict = filter.offer(raw)
        val stored = raw.copy(isFilteredOut = !verdict.accepted)
        bufferLock.withLock {
            // Reinstated jump candidates were stored as rejected; re-store them as accepted copies.
            verdict.reinstated.forEach { buffer += it.copy(isFilteredOut = false) }
            buffer += stored
        }
        stateHolder.update { s ->
            if (verdict.accepted) {
                s.copy(distanceM = s.distanceM + verdict.addedDistanceM, acceptedPoints = s.acceptedPoints + 1, lastPoint = raw, currentSpeedMps = raw.speedMps?.toDouble())
            } else {
                s.copy(rejectedPoints = s.rejectedPoints + 1)
            }
        }
        val now = System.currentTimeMillis()
        if (buffer.size >= FLUSH_POINTS || now - lastFlushMs > FLUSH_INTERVAL_MS) flush()
        if (now - lastNotifyMs > NOTIFY_INTERVAL_MS) {
            lastNotifyMs = now
            notifications.notify(stateHolder.state.value, now)
        }
    }

    private suspend fun flush() {
        val batch = bufferLock.withLock { if (buffer.isEmpty()) return else buffer.toList().also { buffer.clear() } }
        repository.insertPoints(batch)
        lastFlushMs = System.currentTimeMillis()
    }

    private fun pause(record: Boolean = true) {
        val id = segmentId ?: return
        fused.removeLocationUpdates(callback)
        val now = System.currentTimeMillis()
        stateHolder.update { it.copy(isPaused = true, pausedSinceUtcMs = now, currentSpeedMps = null) }
        lifecycleScope.launch {
            flush()
            if (record) repository.pause(id, PauseKind.USER)
            notifications.notify(stateHolder.state.value, now)
        }
    }

    private fun resume() {
        val id = segmentId ?: return
        val now = System.currentTimeMillis()
        stateHolder.update { s ->
            val pausedFor = s.pausedSinceUtcMs?.let { now - it } ?: 0
            s.copy(isPaused = false, pausedSinceUtcMs = null, pausedAccumMs = s.pausedAccumMs + pausedFor)
        }
        // New track segment after a pause: the first point must not add distance from the pre-pause point.
        filter = PointFilter(FilterConfig.forProfile(profile))
        lifecycleScope.launch {
            repository.resume(id)
            notifications.notify(stateHolder.state.value, now)
        }
        requestUpdates(profile)
    }

    private fun stop() {
        fused.removeLocationUpdates(callback)
        lifecycleScope.launch {
            flush()
            stateHolder.update { it.copy(isRunning = false) }
            ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        // The one accepted runBlocking: never lose buffered points on teardown.
        runBlocking { flush() }
        super.onDestroy()
    }

    companion object {
        const val TAG = "Bitacora"
        const val ACTION_START = "com.bitacoraviaje.app.tracking.START"
        const val ACTION_PAUSE = "com.bitacoraviaje.app.tracking.PAUSE"
        const val ACTION_RESUME = "com.bitacoraviaje.app.tracking.RESUME"
        const val ACTION_STOP = "com.bitacoraviaje.app.tracking.STOP"
        const val EXTRA_SEGMENT_ID = "segment_id"
        const val EXTRA_PROFILE = "profile"
        const val FLUSH_POINTS = 10
        const val FLUSH_INTERVAL_MS = 30_000L
        const val NOTIFY_INTERVAL_MS = 5_000L
    }
}
