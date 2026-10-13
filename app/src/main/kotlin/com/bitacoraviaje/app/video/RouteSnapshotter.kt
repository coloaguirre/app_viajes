package com.bitacoraviaje.app.video

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.bitacoraviaje.app.BuildConfig
import com.bitacoraviaje.core.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/**
 * Static base map of the stage from MapLibre's [MapSnapshotter] (MapTiler tiles, decision D2),
 * when the key is configured and tiles arrive within [TIMEOUT_MS]. Otherwise null and the
 * route is drawn over a plain ground: the mini video never depends on the network.
 */
class RouteSnapshotter(private val context: Context) {
    /** The base map and the track projected with the snapshot's own camera (review I2), in bitmap pixels. */
    class RouteBase(val bitmap: Bitmap, val projected: List<Pair<Float, Float>>)

    suspend fun snapshot(points: List<TrackPoint>, width: Int, height: Int): RouteBase? {
        if (BuildConfig.MAPTILER_KEY.isBlank() || points.size < 2) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                runCatching {
                    suspendCancellableCoroutine<RouteBase?> { cont ->
                        val raw = LatLngBounds.Builder().also { bb -> points.forEach { bb.include(LatLng(it.lat, it.lon)) } }.build()
                        val b = padded(raw, PADDING)
                        val options = MapSnapshotter.Options(width.coerceAtMost(MAX_SIDE), height.coerceAtMost(MAX_SIDE))
                            .withStyle(STYLE)
                            .withRegion(b)
                            .withCameraPosition(CameraPosition.Builder().target(b.center).build())
                            .withLogo(false)
                        val snapshotter = MapSnapshotter(context, options)
                        cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }
                        snapshotter.start(
                            { snap ->
                                val projected = points.map { p -> snap.pixelForLatLng(LatLng(p.lat, p.lon)).let { it.x to it.y } }
                                if (cont.isActive) cont.resume(RouteBase(snap.bitmap, projected))
                            },
                            { err ->
                                // The style url carries the MapTiler key: never echo the raw error (review M4).
                                Log.w(TAG, "map snapshot failed (${err?.length ?: 0} chars); drawing the route on a plain ground")
                                if (cont.isActive) cont.resume(null)
                            },
                        )
                    }
                }.getOrElse { Log.w(TAG, "map snapshot unavailable: ${it.javaClass.simpleName}"); null }
            }
        }
    }

    /** Bounds grown by [fraction] on every side so the route does not touch the frame. */
    private fun padded(b: LatLngBounds, fraction: Double): LatLngBounds {
        val dLat = maxOf(1e-4, b.latitudeSpan * fraction)
        val dLon = maxOf(1e-4, b.longitudeSpan * fraction)
        return LatLngBounds.from(b.latitudeNorth + dLat, b.longitudeEast + dLon, b.latitudeSouth - dLat, b.longitudeWest - dLon)
    }

    companion object {
        private const val TAG = "Bitacora"
        const val TIMEOUT_MS = 15_000L
        const val PADDING = 0.15
        const val MAX_SIDE = 2048
        val STYLE = "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_KEY}"
    }
}
