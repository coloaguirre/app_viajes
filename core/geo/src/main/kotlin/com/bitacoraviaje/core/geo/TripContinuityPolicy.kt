package com.bitacoraviaje.core.geo

import com.bitacoraviaje.core.model.DayBoundary
import com.bitacoraviaje.core.model.TrackPoint

/** What the recording service must do to keep the continuous trip consistent. */
sealed interface ContinuityAction {
    /** Close the current daily segment and open the next one (local midnight passed). */
    data object CutDay : ContinuityAction

    /** User has been still long enough: lower sampling, record an automatic pause. */
    data object EnterRest : ContinuityAction

    /** Movement detected after a rest: restore sampling, close the automatic pause. */
    data object ExitRest : ContinuityAction
}

/**
 * Pure state machine for adenda 1 ("the trip does not end until the user says so").
 * The service feeds accepted points and periodic ticks; this decides when to cut the
 * day and when to enter/leave rest. No clock inside: time comes from the caller.
 */
class TripContinuityPolicy(
    private val tzId: String,
    segmentStartedUtcMs: Long,
    restConfig: RestConfig = RestConfig(),
    private val cutDays: Boolean = true,
) {
    private var nextMidnightUtcMs: Long = DayBoundary.nextLocalMidnightUtcMs(segmentStartedUtcMs, tzId)
    private val rest = RestDetector(restConfig)

    val isResting: Boolean get() = rest.isResting

    fun onPoint(p: TrackPoint): List<ContinuityAction> {
        val out = ArrayList<ContinuityAction>(2)
        // Day cut only here: the rest decision for this point is made by offer() below.
        if (cutDays && p.timeUtcMs >= nextMidnightUtcMs) {
            while (p.timeUtcMs >= nextMidnightUtcMs) nextMidnightUtcMs = DayBoundary.nextLocalMidnightUtcMs(nextMidnightUtcMs, tzId)
            out += ContinuityAction.CutDay
        }
        when (rest.offer(p)) {
            RestEvent.ENTER -> out += ContinuityAction.EnterRest
            RestEvent.EXIT -> out += ContinuityAction.ExitRest
            null -> Unit
        }
        return out
    }

    fun onTick(nowUtcMs: Long): List<ContinuityAction> {
        val out = ArrayList<ContinuityAction>(2)
        if (cutDays && nowUtcMs >= nextMidnightUtcMs) {
            // Advance past every midnight crossed (a long gap could skip a whole day).
            while (nowUtcMs >= nextMidnightUtcMs) nextMidnightUtcMs = DayBoundary.nextLocalMidnightUtcMs(nextMidnightUtcMs, tzId)
            out += ContinuityAction.CutDay
        }
        if (rest.onTick(nowUtcMs) == RestEvent.ENTER) out += ContinuityAction.EnterRest
        return out
    }

    /** User paused or resumed manually: forget the rest anchor so a stale one cannot fire. */
    fun resetRest() = rest.reset()
}
