package com.bitacoraviaje.app.tracking

import android.location.Location
import android.os.Build
import com.bitacoraviaje.core.model.TrackPoint

/** Android Location → domain point. The only place that knows about android.location. */
fun Location.toTrackPoint(segmentId: String): TrackPoint = TrackPoint(
    segmentId = segmentId,
    timeUtcMs = time,
    lat = latitude,
    lon = longitude,
    altM = if (hasAltitude()) altitude else null,
    altMslM = if (Build.VERSION.SDK_INT >= 34 && hasMslAltitude()) mslAltitudeMeters else null,
    hAccuracyM = if (hasAccuracy()) accuracy else null,
    vAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingDeg = if (hasBearing()) bearing else null,
    provider = provider,
    isMock = if (Build.VERSION.SDK_INT >= 31) isMock else false,
    monotonicMs = elapsedRealtimeNanos / 1_000_000,
)
