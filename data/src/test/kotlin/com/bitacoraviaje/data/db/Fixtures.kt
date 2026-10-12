package com.bitacoraviaje.data.db

import com.bitacoraviaje.data.db.entity.SegmentEntity
import com.bitacoraviaje.data.db.entity.TrackPointEntity
import com.bitacoraviaje.data.db.entity.TripEntity

/** Synthetic rows for DAO tests. Deterministic: no clocks, no randomness. */
object Fixtures {
    const val T0 = 1_760_000_000_000L // 2025-10-09T08:53:20Z, arbitrary fixed instant

    fun trip(id: String = "t1", status: String = "ACTIVE") = TripEntity(
        id = id, title = "Trip $id", description = null, createdUtcMs = T0,
        startedUtcMs = T0, endedUtcMs = null, coverMediaId = null, status = status,
    )

    fun segment(id: String = "s1", tripId: String = "t1", status: String = "ACTIVE", startedUtcMs: Long = T0, parent: String? = null) = SegmentEntity(
        id = id, tripId = tripId, parentSegmentId = parent, type = "STAGE", transport = "WALK", title = null,
        intentText = null, startedUtcMs = startedUtcMs, endedUtcMs = null, tzId = "America/Argentina/Buenos_Aires",
        samplingProfile = "WALK", recordingMode = "FULL", autoCreated = false, status = status,
        distanceM = null, movingTimeMs = null, totalTimeMs = null, maxSpeedMps = null, avgMovingSpeedMps = null,
        elevationGainM = null, elevationLossM = null, minAltM = null, maxAltM = null,
        bboxMinLat = null, bboxMinLon = null, bboxMaxLat = null, bboxMaxLon = null,
    )

    fun point(segmentId: String = "s1", i: Int, filtered: Boolean = false) = TrackPointEntity(
        segmentId = segmentId, timeUtcMs = T0 + i * 5_000L, lat = -34.6 + i * 1e-4, lon = -58.4,
        altM = 20.0, altMslM = null, hAccuracyM = 5f, vAccuracyM = null, speedMps = 1.2f, speedAccuracyMps = null,
        bearingDeg = null, provider = "fused", activity = null, isMock = false, isFilteredOut = filtered, isSimplifiedKeep = true,
    )
}
