package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.SamplingProfile
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport

/** Thresholds per transport/profile (DISENO §6.4). */
data class FilterConfig(
    val maxAccuracyM: Double,
    val maxSpeedMps: Double,
    val stillSpeedMps: Double = 0.5,
    val stillRadiusM: Double = 15.0,
    /** A rejected jump becomes accepted if this many following fixes confirm the new area. */
    val jumpConfirmations: Int = 3,
) {
    companion object {
        val WALK = FilterConfig(maxAccuracyM = 25.0, maxSpeedMps = 4.0)
        val BIKE = FilterConfig(maxAccuracyM = 25.0, maxSpeedMps = 20.0)
        val VEHICLE = FilterConfig(maxAccuracyM = 50.0, maxSpeedMps = 70.0)
        val PLANE = FilterConfig(maxAccuracyM = 200.0, maxSpeedMps = 300.0)

        fun forProfile(profile: SamplingProfile): FilterConfig = when (profile) {
            SamplingProfile.TREKKING_HIGH, SamplingProfile.WALK -> WALK
            SamplingProfile.BIKE -> BIKE
            SamplingProfile.VEHICLE -> VEHICLE
            SamplingProfile.PLANE -> PLANE
            // Battery modes relax accuracy (balanced power yields 20-50 m fixes) but keep a vehicle speed cap.
            SamplingProfile.SAVER, SamplingProfile.ECONOMY, SamplingProfile.REST -> FilterConfig(maxAccuracyM = 100.0, maxSpeedMps = 70.0)
        }

        fun forTransport(transport: Transport?): FilterConfig = when (transport) {
            Transport.WALK, null -> WALK
            Transport.BIKE -> BIKE
            Transport.CAR, Transport.BUS, Transport.TRAIN, Transport.BOAT, Transport.MIXED -> VEHICLE
            Transport.PLANE -> PLANE
        }
    }
}

enum class RejectReason { DUPLICATE_TIME, LOW_ACCURACY, IMPOSSIBLE_JUMP, MOCK }

/**
 * Verdict for one incoming fix. [addedDistanceM] is the distance the fix adds to the
 * segment total (0 while still, 0 when rejected). [reinstated] lists previously
 * rejected jump candidates that this fix confirmed, in order; they must be stored
 * as accepted too.
 */
data class FilterVerdict(
    val accepted: Boolean,
    val reason: RejectReason? = null,
    val addedDistanceM: Double = 0.0,
    val reinstated: List<TrackPoint> = emptyList(),
)

/**
 * Stateful online filter: feed fixes in arrival order. Pure Kotlin, no clock; the
 * caller persists every point with `isFilteredOut` set from the verdict.
 *
 * Rules, in order: duplicate or backwards time; accuracy above threshold; implied
 * speed from the last accepted point above the maximum (unless the next
 * [FilterConfig.jumpConfirmations] fixes confirm the new area: a real jump after a
 * long gap); while still, movement inside [FilterConfig.stillRadiusM] adds no distance.
 */
class PointFilter(private val config: FilterConfig, private val rejectMock: Boolean = false) {
    var lastAccepted: TrackPoint? = null
        private set

    /** Anchor of the current "still" cluster, if the user is standing still. */
    private var stillAnchor: TrackPoint? = null
    private val pendingJump = ArrayList<TrackPoint>()

    /**
     * Seed the filter with the last accepted point of a segment being resumed, so the
     * first new fix is judged against it. Without this, a stale or bogus first fix
     * after a process restart is accepted unconditionally (measured 2026-10-12: the
     * emulator's default location 10 000 km away was accepted after a restart).
     */
    fun seed(last: TrackPoint) {
        lastAccepted = last
        stillAnchor = last
        pendingJump.clear()
    }

    fun offer(p: TrackPoint): FilterVerdict {
        val last = lastAccepted
        if (last != null && p.timeUtcMs <= last.timeUtcMs) return FilterVerdict(false, RejectReason.DUPLICATE_TIME)
        if (rejectMock && p.isMock) return FilterVerdict(false, RejectReason.MOCK)
        val acc = p.hAccuracyM
        if (acc != null && acc > config.maxAccuracyM) return FilterVerdict(false, RejectReason.LOW_ACCURACY)
        if (last == null) {
            lastAccepted = p
            stillAnchor = p
            return FilterVerdict(true, addedDistanceM = 0.0)
        }

        val dist = haversineM(last.lat, last.lon, p.lat, p.lon)
        val dtS = (p.timeUtcMs - last.timeUtcMs) / 1000.0
        val impliedSpeed = dist / dtS
        if (impliedSpeed > config.maxSpeedMps) {
            return handleJumpCandidate(p)
        }
        pendingJump.clear()
        return accept(p, dist, impliedSpeed)
    }

    private fun handleJumpCandidate(p: TrackPoint): FilterVerdict {
        // Is p consistent with the pending jump cluster (new area after a gap)?
        val first = pendingJump.firstOrNull()
        if (first != null) {
            val d = haversineM(first.lat, first.lon, p.lat, p.lon)
            val dt = (p.timeUtcMs - pendingJump.last().timeUtcMs) / 1000.0
            if (d / maxOf(dt, 1.0) > config.maxSpeedMps) {
                pendingJump.clear()
            }
        }
        pendingJump.add(p)
        if (pendingJump.size > config.jumpConfirmations) {
            // Confirmed: the first candidate was a real relocation. Reinstate all,
            // without counting the teleport distance (we do not know the path).
            val reinstated = pendingJump.toList()
            pendingJump.clear()
            var prev = reinstated.first()
            var added = 0.0
            for (q in reinstated.drop(1)) {
                added += haversineM(prev.lat, prev.lon, q.lat, q.lon)
                prev = q
            }
            lastAccepted = reinstated.last()
            stillAnchor = reinstated.last()
            return FilterVerdict(true, addedDistanceM = added, reinstated = reinstated.dropLast(1))
        }
        return FilterVerdict(false, RejectReason.IMPOSSIBLE_JUMP)
    }

    private fun accept(p: TrackPoint, dist: Double, impliedSpeed: Double): FilterVerdict {
        val reported = p.speedMps?.toDouble()
        val speed = reported ?: impliedSpeed
        val anchor = stillAnchor
        val added: Double
        if (speed < config.stillSpeedMps && anchor != null && haversineM(anchor.lat, anchor.lon, p.lat, p.lon) <= config.stillRadiusM) {
            added = 0.0 // GPS wobble while standing still
        } else {
            added = if (anchor != null && lastAccepted !== anchor && speed < config.stillSpeedMps) 0.0 else dist
            stillAnchor = p
        }
        lastAccepted = p
        return FilterVerdict(true, addedDistanceM = added)
    }
}
