package com.bitacoraviaje.app.video

import com.bitacoraviaje.core.videoplan.PlanInput
import com.bitacoraviaje.core.videoplan.PlanParams
import com.bitacoraviaje.core.videoplan.Planner
import com.bitacoraviaje.core.videoplan.VideoPlan
import javax.inject.Inject

/**
 * The adenda-1 preset: a 1-minute video of one stage (PLAN 4.6). The planner input comes from
 * [TripVideoPlanner] (one Room → PlanInput rule for the stage and the trip videos, review M6).
 */
class MiniVideoPlanner @Inject constructor(private val trips: TripVideoPlanner) {
    data class Prepared(val segment: com.bitacoraviaje.core.model.Segment, val input: PlanInput, val plan: VideoPlan)

    /** Media without a segment (unlinked) travels with the first segment, as in the export; a stage video only uses its own. */
    suspend fun prepare(segmentId: String): Prepared? {
        val segment = trips.segment(segmentId) ?: return null
        val prepared = trips.input(segment.tripId, onlySegmentId = segmentId) ?: return null
        return Prepared(segment, prepared.input, Planner.plan(prepared.input, PlanParams.quickOneMinute(segmentId)))
    }

    companion object {
        /** The static map of the stage comes from MapTiler/OSM when the key is configured (D2). */
        const val TILES_CREDIT = "Mapa: © MapTiler © OpenStreetMap contributors"
    }
}
