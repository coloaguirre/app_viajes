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
    suspend fun snapshot(points: List<TrackPoint>, width: Int, height: Int): Bitmap? {
        if (BuildConfig.MAPTILER_KEY.isBlank() || points.size < 2) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                runCatching {
                    suspendCancellableCoroutine<Bitmap?> { cont ->
                        val b = LatLngBounds.Builder().also { bb -> points.forEach { bb.include(LatLng(it.lat, it.lon)) } }.build()
                        val options = MapSnapshotter.Options(width.coerceAtMost(MAX_SIDE), height.coerceAtMost(MAX_SIDE))
                            .withStyle(STYLE)
                            .withRegion(b)
                            .withCameraPosition(CameraPosition.Builder().target(b.center).build())
                            .withLogo(false)
                        val snapshotter = MapSnapshotter(context, options)
                        cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }
                        snapshotter.start({ snap -> if (cont.isActive) cont.resume(snap.bitmap) }, { err -> Log.w(TAG, "map snapshot failed: $err"); if (cont.isActive) cont.resume(null) })
                    }
                }.getOrElse { Log.w(TAG, "map snapshot unavailable: ${it.message}"); null }
            }
        }
    }

    companion object {
        private const val TAG = "Bitacora"
        const val TIMEOUT_MS = 15_000L
        const val MAX_SIDE = 2048
        val STYLE = "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_KEY}"
    }
}
