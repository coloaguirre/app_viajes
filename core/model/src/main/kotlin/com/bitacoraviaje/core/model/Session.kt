package com.bitacoraviaje.core.model

/** What the user declared when starting a recording session (DISENO §1.5). */
enum class SegmentType {
    TRIP_START,
    STAGE,
    EXCURSION,
    TREKKING,
}

/** Means of transport; drives the GPS sampling profile (DISENO §6.2). */
enum class Transport {
    WALK,
    BIKE,
    CAR,
    BUS,
    TRAIN,
    BOAT,
    PLANE,
    MIXED,
}

/**
 * User-chosen recording mode (pedido original, adenda 1).
 * FULL keeps the per-transport profiles; ECONOMY trades detail for battery on
 * long treks without a chance to recharge.
 */
enum class RecordingMode {
    FULL,
    ECONOMY,
}

/** Lifecycle of a trip. A trip stays ACTIVE until the user ends it. */
enum class TripStatus {
    PLANNED,
    ACTIVE,
    PAUSED,
    FINISHED,
}

/** Display units. Lives in core so exporters and the UI format distances the same way. */
enum class Units {
    METRIC,
    IMPERIAL,
}
