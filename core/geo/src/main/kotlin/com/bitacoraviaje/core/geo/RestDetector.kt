package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.TrackPoint

/** Thresholds for the automatic rest state (adenda 1: continuous trip). */
data class RestConfig(
    /** Radius the user must stay inside to be considered still. */
    val enterRadiusM: Double = 30.0,
    /** How long inside the radius before entering rest. */
    val enterAfterMs: Long = 5 * 60_000,
    /** Distance from the rest anchor that ends the rest. Larger than enterRadius: hysteresis. */
    val exitRadiusM: Double = 60.0,
)

enum class RestEvent { ENTER, EXIT }

/**
 * Online stillness detector. Feed ACCEPTED points in time order and periodic
 * ticks. Two ways in: points clustered within [RestConfig.enterRadiusM] for
 * [RestConfig.enterAfterMs], or **silence** for that long ([onTick]): location
 * providers stop delivering fixes when the device does not move past the request's
 * minimum distance, so "no points" is the normal signature of standing still
 * (measured on the emulator 2026-10-12). EXIT when a point leaves
 * [RestConfig.exitRadiusM] of the rest anchor. Pure, no clock inside.
 */
class RestDetector(private val config: RestConfig = RestConfig()) {
    var isResting: Boolean = false
        private set
    private var anchor: TrackPoint? = null

    fun offer(p: TrackPoint): RestEvent? {
        val a = anchor
        if (a == null) {
            anchor = p
            return null
        }
        val d = haversineM(a.lat, a.lon, p.lat, p.lon)
        if (isResting) {
            if (d > config.exitRadiusM) {
                isResting = false
                anchor = p
                return RestEvent.EXIT
            }
            return null
        }
        if (d > config.enterRadiusM) {
            anchor = p
            return null
        }
        if (p.timeUtcMs - a.timeUtcMs >= config.enterAfterMs) {
            isResting = true
            return RestEvent.ENTER
        }
        return null
    }

    /** Time-based entry: no point for [RestConfig.enterAfterMs] since the last one means still. */
    fun onTick(nowUtcMs: Long): RestEvent? {
        val a = anchor ?: return null
        if (isResting) return null
        if (nowUtcMs - a.timeUtcMs >= config.enterAfterMs) {
            isResting = true
            return RestEvent.ENTER
        }
        return null
    }

    fun reset() {
        isResting = false
        anchor = null
    }
}
