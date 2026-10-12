package com.bitacoraviaje.app.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A WGS84 position for the map layer; keeps the UI independent of the map SDK types. */
data class MapPoint(val lat: Double, val lon: Double)

/** What the live/detail map shows. All inputs are plain data so renderers are swappable. */
data class TrackMapState(
    val track: List<MapPoint> = emptyList(),
    val current: MapPoint? = null,
    val stops: List<MapPoint> = emptyList(),
    /** Follow the current position until the user pans; the renderer owns the "user moved" flag. */
    val follow: Boolean = true,
)

/**
 * DISENO §10.1: abstraction over the map SDK. v1 ships MapLibre + MapTiler; a Google
 * Maps implementation can be added behind this interface without touching screens.
 */
interface MapRenderer {
    /** Whether the renderer can show a base map (e.g. the tile key is configured). */
    val isConfigured: Boolean

    @Composable
    fun TrackMap(state: TrackMapState, modifier: Modifier)
}
