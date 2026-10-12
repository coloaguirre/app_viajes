package com.bitacoraviaje.core.model

/** Location request priority, platform-agnostic. Mapped to Play Services constants in :app. */
enum class LocationPriority { HIGH_ACCURACY, BALANCED_POWER }

/**
 * GPS sampling profiles (DISENO §6.2). ECONOMY and REST were added for the
 * continuous trip of adenda 1: ECONOMY is the user-chosen battery mode, REST is
 * the automatic low-power state while the user stays still.
 *
 * ECONOMY keeps HIGH_ACCURACY on purpose: it is meant for long treks where there
 * is no Wi-Fi or cell coverage, so BALANCED_POWER would have nothing to fall
 * back on (and the emulator never delivers GPS fixes to it: measured 2026-10-12,
 * 1 point in 10 minutes). The battery saving comes from the long interval and
 * the batching delay, which let the GPS chip sleep between fixes.
 */
enum class SamplingProfile(
    val priority: LocationPriority,
    val intervalMs: Long,
    val minIntervalMs: Long,
    val minDistanceM: Float,
    val maxDelayMs: Long,
) {
    TREKKING_HIGH(LocationPriority.HIGH_ACCURACY, 3_000, 2_000, 3f, 0),
    WALK(LocationPriority.HIGH_ACCURACY, 5_000, 3_000, 5f, 0),
    BIKE(LocationPriority.HIGH_ACCURACY, 4_000, 2_000, 8f, 0),
    VEHICLE(LocationPriority.HIGH_ACCURACY, 10_000, 5_000, 30f, 30_000),
    PLANE(LocationPriority.BALANCED_POWER, 60_000, 30_000, 500f, 120_000),
    SAVER(LocationPriority.BALANCED_POWER, 30_000, 15_000, 50f, 60_000),
    ECONOMY(LocationPriority.HIGH_ACCURACY, 30_000, 20_000, 40f, 60_000),
    // REST: high accuracy for the same reason as ECONOMY; 50 m min distance so leaving the rest radius (60 m) is seen promptly.
    // No batching while resting: a batched fix would delay the exit by up to maxDelay (measured 2026-10-12).
    REST(LocationPriority.HIGH_ACCURACY, 60_000, 30_000, 40f, 0),
    ;

    companion object {
        /** Profile for a session as declared by the user. ECONOMY overrides everything but PLANE. */
        fun forSession(type: SegmentType, transport: Transport?, mode: RecordingMode): SamplingProfile {
            if (transport == Transport.PLANE) return PLANE
            if (mode == RecordingMode.ECONOMY) return ECONOMY
            if (type == SegmentType.TREKKING) return TREKKING_HIGH
            return when (transport) {
                Transport.WALK, null -> WALK
                Transport.BIKE -> BIKE
                Transport.CAR, Transport.BUS, Transport.TRAIN, Transport.BOAT, Transport.MIXED -> VEHICLE
                Transport.PLANE -> PLANE
            }
        }
    }
}
