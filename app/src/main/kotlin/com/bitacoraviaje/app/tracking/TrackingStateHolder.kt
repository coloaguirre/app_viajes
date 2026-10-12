package com.bitacoraviaje.app.tracking

import com.bitacoraviaje.core.model.SamplingProfile
import com.bitacoraviaje.core.model.TrackPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Live state published by [TrackingService]; the UI observes this, never binds the service. */
data class LiveTrackingState(
    val segmentId: String? = null,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    /** Automatic rest (adenda 1): still for a while, GPS at minimum, trip still open. */
    val isResting: Boolean = false,
    val startedUtcMs: Long = 0,
    val pausedAccumMs: Long = 0,
    val pausedSinceUtcMs: Long? = null,
    val distanceM: Double = 0.0,
    val acceptedPoints: Int = 0,
    val rejectedPoints: Int = 0,
    val lastPoint: TrackPoint? = null,
    val currentSpeedMps: Double? = null,
    val gpsAvailable: Boolean = true,
    val profile: SamplingProfile = SamplingProfile.WALK,
) {
    fun elapsedMs(nowUtcMs: Long): Long {
        if (startedUtcMs == 0L) return 0
        val pausedNow = pausedSinceUtcMs?.let { nowUtcMs - it } ?: 0
        return (nowUtcMs - startedUtcMs - pausedAccumMs - pausedNow).coerceAtLeast(0)
    }
}

@Singleton
class TrackingStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(LiveTrackingState())
    val state: StateFlow<LiveTrackingState> = _state

    fun update(transform: (LiveTrackingState) -> LiveTrackingState) = _state.update(transform)

    fun reset() = _state.update { LiveTrackingState() }
}
