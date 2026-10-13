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

/** Lifecycle of a segment. RECOVERED = closed by the recovery flow, not by the user. */
enum class SegmentStatus {
    ACTIVE,
    PAUSED,
    FINISHED,
    RECOVERED,
}

/**
 * Why a segment was paused. USER = pause button; AUTO_REST = resting state after
 * being still (adenda 1: continuous trip); NESTED = a child excursion/trekking
 * is recording and the parent waits.
 */
enum class PauseKind {
    USER,
    AUTO_REST,
    NESTED,
    /** Export only (never stored): the track was cut because it entered a privacy zone (DISENO §15.3, PLAN 5.5). */
    PRIVACY,
}

/** A circle the exported trip must not reveal (home, work): points inside are dropped and media there loses its location. */
data class PrivacyZone(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Double)

/** Display units. Lives in core so exporters and the UI format distances the same way. */
enum class Units {
    METRIC,
    IMPERIAL,
}
