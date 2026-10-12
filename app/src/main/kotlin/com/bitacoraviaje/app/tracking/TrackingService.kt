package com.bitacoraviaje.app.tracking

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.core.geo.ClockGuard
import com.bitacoraviaje.core.geo.ContinuityAction
import com.bitacoraviaje.core.geo.FilterConfig
import com.bitacoraviaje.core.geo.PointFilter
import com.bitacoraviaje.core.geo.RestConfig
import com.bitacoraviaje.core.geo.TripContinuityPolicy
import com.bitacoraviaje.core.model.LocationPriority
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.SamplingProfile
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.repository.TrackingRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground service of type `location` (DISENO §6.1). Started only from the visible
 * app; survives screen off; publishes live state through [TrackingStateHolder].
 *
 * Concurrency: every mutation of the recording pipeline (clock guard → filter →
 * continuity policy → buffer) runs on [pipeline], a single-thread dispatcher, so
 * location batches, the ticker and commands never race on the non-thread-safe
 * pure objects. Points are buffered and flushed every [FLUSH_POINTS] fixes or
 * [FLUSH_INTERVAL_MS], whichever comes first (the ticker covers silent periods).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class TrackingService : LifecycleService() {
    @Inject lateinit var fused: FusedLocationProviderClient

    @Inject lateinit var repository: TrackingRepository

    @Inject lateinit var stateHolder: TrackingStateHolder

    @Inject lateinit var notifications: TrackingNotificationFactory

    @Inject lateinit var prefs: UserPreferences

    private val pipeline = Dispatchers.Default.limitedParallelism(1)

    private var segmentId: String? = null
    private var transport: Transport? = null
    /** Profile the user asked for; [profile] may be lowered to SAVER on low battery (DISENO §6.2). */
    private var requestedProfile: SamplingProfile = SamplingProfile.WALK
    private var profile: SamplingProfile = SamplingProfile.WALK
    private var batterySaver = false
    private var filter: PointFilter = PointFilter(FilterConfig.WALK)
    private var clockGuard = ClockGuard()
    private var loggedCorrections = 0
    private var policy: TripContinuityPolicy? = null
    private val buffer = ArrayList<TrackPoint>()
    private var lastFlushMs = 0L
    private var lastNotifyMs = 0L
    private var tickerJob: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = segmentId ?: return
            val points = result.locations.map { it.toTrackPoint(id) }
            lifecycleScope.launch(pipeline) { points.forEach { onPoint(it) } }
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
            ACTION_PAUSE -> lifecycleScope.launch(pipeline) { pause(record = true) }
            ACTION_RESUME -> lifecycleScope.launch(pipeline) { resume() }
            ACTION_STOP -> stop()
            null -> recoverFromRestart()
        }
        return START_STICKY
    }

    private fun start(id: String, p: SamplingProfile) {
        notifications.ensureChannel()
        val now = System.currentTimeMillis()
        stateHolder.update { LiveTrackingState(segmentId = id, isRunning = true, startedUtcMs = now, profile = p) }
        // minSdk 26: the typed constant exists from API 29; ServiceCompat ignores the type below that.
        @Suppress("InlinedApi")
        val fgsType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, TrackingNotificationFactory.NOTIFICATION_ID, notifications.build(stateHolder.state.value, now), fgsType)
        lifecycleScope.launch(pipeline) {
            // Previous segment (hand-back, day cut, restart on another id): persist what it has.
            flush()
            segmentId = id
            requestedProfile = p
            profile = p
            batterySaver = false
            val segment = repository.getSegment(id)
            transport = segment?.transport
            filter = PointFilter(FilterConfig.forProfile(p, transport))
            clockGuard = ClockGuard()
            loggedCorrections = 0
            // Seed filter and clock guard with the segment's last fix BEFORE asking for updates:
            // a resumed segment judges its first new fix against where it left off.
            repository.lastValidPoint(id)?.let {
                filter.seed(it)
                clockGuard.seed(it.timeUtcMs, it.monotonicMs)
            }
            // Live state continues from what the database already holds (I9).
            if (segment != null) {
                val r = repository.rehydrate(id, now)
                stateHolder.update { s ->
                    s.copy(
                        startedUtcMs = segment.startedUtcMs,
                        distanceM = r.distanceM,
                        acceptedPoints = r.acceptedPoints,
                        pausedAccumMs = r.pausedAccumMs,
                        pausedSinceUtcMs = r.openPauseSinceUtcMs,
                    )
                }
            }
            installPolicy(segment?.tzId ?: TimeZone.getDefault().id, segment?.startedUtcMs ?: now, cutDays = segment?.parentSegmentId == null)
            requestUpdates(p)
            // Resuming a segment the database says is PAUSED (recovery flow): keep it paused.
            if (segment?.status == SegmentStatus.PAUSED) pause(record = false)
            notifications.cancelResumeNeeded()
            Log.i(TAG, "tracking started segment=$id profile=$p")
        }
    }

    /** START_STICKY restart after the system killed us: resume the open segment or quit. */
    private fun recoverFromRestart() {
        lifecycleScope.launch {
            val open = repository.getOpenSegment()
            if (open == null) {
                stopSelf()
                return@launch
            }
            start(open.id, SamplingProfile.valueOf(open.samplingProfile))
            Log.i(TAG, "recovered segment=${open.id} status=${open.status}")
        }
    }

    /** Continuous-trip policy for the current segment: day cuts (not for nested ones) and automatic rest. */
    private suspend fun installPolicy(tzId: String, startedUtcMs: Long, cutDays: Boolean) {
        val restAfterMin = prefs.settings.first().restAfterMin
        policy = TripContinuityPolicy(
            tzId = tzId,
            segmentStartedUtcMs = startedUtcMs,
            restConfig = RestConfig(enterAfterMs = restAfterMin * 60_000L),
            cutDays = cutDays,
        )
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch(pipeline) {
            while (true) {
                delay(TICK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val live = stateHolder.state.value
                // A manual pause freezes the policy: no rest entry, no GPS re-request (I5).
                if (!live.isPaused) policy?.onTick(now)?.forEach { apply(it) }
                if (buffer.isNotEmpty() && now - lastFlushMs > FLUSH_INTERVAL_MS) flush() // I1: silence flushes too
                checkBattery()
                notifications.notify(stateHolder.state.value, now)
            }
        }
    }

    private suspend fun apply(action: ContinuityAction) {
        val id = segmentId ?: return
        when (action) {
            ContinuityAction.CutDay -> {
                flush()
                val next = repository.cutDay(id, currentTzId = TimeZone.getDefault().id) ?: return
                segmentId = next.id
                filter = PointFilter(FilterConfig.forProfile(profile, transport))
                val now = System.currentTimeMillis()
                stateHolder.update { s -> s.copy(segmentId = next.id, startedUtcMs = now, pausedAccumMs = 0, pausedSinceUtcMs = if (s.isPaused || s.isResting) now else null, distanceM = 0.0, acceptedPoints = 0, rejectedPoints = 0) }
                // New day, possibly new time zone: refresh the policy (keeps rest thresholds).
                val resting = stateHolder.state.value.isResting
                installPolicy(next.tzId, now, cutDays = true)
                if (resting) requestUpdates(SamplingProfile.REST)
                Log.i(TAG, "day cut: new segment=${next.id} tz=${next.tzId}")
            }
            ContinuityAction.EnterRest -> {
                if (stateHolder.state.value.isPaused) return
                flush()
                repository.pause(id, PauseKind.AUTO_REST)
                val now = System.currentTimeMillis()
                stateHolder.update { it.copy(isResting = true, pausedSinceUtcMs = now, currentSpeedMps = null) }
                requestUpdates(SamplingProfile.REST)
                notifications.notify(stateHolder.state.value, now)
                Log.i(TAG, "rest entered segment=$id")
            }
            ContinuityAction.ExitRest -> {
                repository.resume(id)
                val now = System.currentTimeMillis()
                stateHolder.update { s ->
                    val restedFor = s.pausedSinceUtcMs?.let { now - it } ?: 0
                    s.copy(isResting = false, pausedSinceUtcMs = null, pausedAccumMs = s.pausedAccumMs + restedFor)
                }
                requestUpdates(profile)
                notifications.notify(stateHolder.state.value, now)
                Log.i(TAG, "rest exited segment=$id")
            }
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

    /** Runs on [pipeline]. */
    private suspend fun onPoint(incoming: TrackPoint) {
        if (stateHolder.state.value.isPaused) return
        val id = segmentId ?: return
        // Wall-clock changes must not reorder or stretch the track: re-derive time from the monotonic clock.
        val fixedUtc = clockGuard.resolve(incoming.timeUtcMs, incoming.monotonicMs)
        val raw = (if (fixedUtc != incoming.timeUtcMs) incoming.copy(timeUtcMs = fixedUtc) else incoming).copy(segmentId = id)
        if (clockGuard.corrections > loggedCorrections) {
            loggedCorrections = clockGuard.corrections
            Log.w(TAG, "wall clock jump detected; fix time corrected from monotonic clock (total $loggedCorrections)")
        }
        val verdict = filter.offer(raw)
        // Reinstated jump candidates were stored as rejected; re-store them as accepted copies.
        verdict.reinstated.forEach { buffer += it.copy(isFilteredOut = false) }
        buffer += raw.copy(isFilteredOut = !verdict.accepted)
        stateHolder.update { s ->
            if (verdict.accepted) {
                s.copy(distanceM = s.distanceM + verdict.addedDistanceM, acceptedPoints = s.acceptedPoints + 1, lastPoint = raw, currentSpeedMps = raw.speedMps?.toDouble())
            } else {
                s.copy(rejectedPoints = s.rejectedPoints + 1)
            }
        }
        if (verdict.accepted) policy?.onPoint(raw)?.forEach { apply(it) }
        val now = System.currentTimeMillis()
        if (buffer.size >= FLUSH_POINTS || now - lastFlushMs > FLUSH_INTERVAL_MS) flush()
        if (now - lastNotifyMs > NOTIFY_INTERVAL_MS) {
            lastNotifyMs = now
            notifications.notify(stateHolder.state.value, now)
        }
    }

    /**
     * Runs on [pipeline]. The buffer is cleared only after the insert succeeded, and the
     * insert cannot be cancelled by scope teardown (I2): a failed or interrupted write
     * keeps the points for the next attempt.
     */
    private suspend fun flush() {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        withContext(NonCancellable) { repository.insertPoints(batch) }
        buffer.subList(0, batch.size).clear()
        lastFlushMs = System.currentTimeMillis()
    }

    /**
     * Below 15 % and not charging, sample like SAVER until the phone is charged again
     * (DISENO §6.2). Uses the sticky battery broadcast: no receiver registration needed.
     */
    private fun checkBattery() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0) return
        val pct = level * 100 / maxOf(scale, 1)
        val wantSaver = pct < LOW_BATTERY_PCT && !charging && requestedProfile != SamplingProfile.ECONOMY && requestedProfile != SamplingProfile.PLANE
        if (wantSaver == batterySaver) return
        batterySaver = wantSaver
        profile = if (wantSaver) SamplingProfile.SAVER else requestedProfile
        val live = stateHolder.state.value
        if (!live.isPaused && !live.isResting) requestUpdates(profile)
        stateHolder.update { it.copy(profile = profile) }
        Log.i(TAG, "battery $pct% charging=$charging -> profile=$profile")
    }

    /** Runs on [pipeline]. */
    private suspend fun pause(record: Boolean) {
        val id = segmentId ?: return
        fused.removeLocationUpdates(callback)
        val now = System.currentTimeMillis()
        stateHolder.update { it.copy(isPaused = true, isResting = false, pausedSinceUtcMs = it.pausedSinceUtcMs ?: now, currentSpeedMps = null) }
        policy?.resetRest()
        flush()
        if (record) repository.pause(id, PauseKind.USER)
        notifications.notify(stateHolder.state.value, now)
    }

    /** Runs on [pipeline]. */
    private suspend fun resume() {
        val id = segmentId ?: return
        val now = System.currentTimeMillis()
        stateHolder.update { s ->
            val pausedFor = s.pausedSinceUtcMs?.let { now - it } ?: 0
            s.copy(isPaused = false, isResting = false, pausedSinceUtcMs = null, pausedAccumMs = s.pausedAccumMs + pausedFor)
        }
        // New track segment after a pause: the first point must not add distance from the pre-pause point.
        filter = PointFilter(FilterConfig.forProfile(profile, transport))
        policy?.resetRest()
        repository.resume(id)
        notifications.notify(stateHolder.state.value, now)
        requestUpdates(profile)
    }

    private fun stop() {
        fused.removeLocationUpdates(callback)
        tickerJob?.cancel()
        lifecycleScope.launch(pipeline) {
            flush()
            stateHolder.update { it.copy(isRunning = false) }
            ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        tickerJob?.cancel()
        // The one accepted runBlocking: never lose buffered points on teardown.
        runBlocking(pipeline) { flush() }
        stateHolder.update { it.copy(isRunning = false) }
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
        /** Notification cadence from points (M9): 15 s is plenty for a shade summary. */
        const val NOTIFY_INTERVAL_MS = 15_000L
        const val TICK_INTERVAL_MS = 60_000L
        const val LOW_BATTERY_PCT = 15
    }
}
