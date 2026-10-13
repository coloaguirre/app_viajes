package com.bitacoraviaje.app.map

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bitacoraviaje.app.BuildConfig
import com.bitacoraviaje.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MapLibre Native + MapTiler tiles (decision D2). The key lives in local.properties →
 * BuildConfig; attribution is rendered by the SDK's built-in control (ODbL / MapTiler).
 */
@Singleton
class MapLibreRenderer @Inject constructor(@ApplicationContext context: Context) : MapRenderer {
    init {
        MapLibre.getInstance(context)
    }

    override val isConfigured: Boolean = BuildConfig.MAPTILER_KEY.isNotBlank()

    private val styleUrl = "https://api.maptiler.com/maps/outdoor-v2/style.json?key=${BuildConfig.MAPTILER_KEY}"

    @Composable
    override fun TrackMap(state: TrackMapState, modifier: Modifier, onMapClick: ((MapPoint) -> Unit)?) {
        if (!isConfigured) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.map_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return
        }
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val mapView = remember { MapView(context) }
        val holder = remember { MapHolder() }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                mapView.onDestroy()
            }
        }

        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                            holder.attach(map, style)
                            holder.latest?.let(holder::render)
                        }
                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) holder.userMoved = true
                        }
                        map.addOnMapClickListener { ll -> holder.onClick?.invoke(MapPoint(ll.latitude, ll.longitude)); holder.onClick != null }
                    }
                }
            },
            update = { holder.onClick = onMapClick; holder.latest = state; holder.render(state) },
            modifier = modifier.fillMaxSize(),
        )
    }

    /** Keeps the GeoJSON sources in sync with the latest state; created once per MapView. */
    private class MapHolder {
        private var map: MapLibreMap? = null
        private var trackSource: GeoJsonSource? = null
        private var pointSource: GeoJsonSource? = null
        private var framedOnce = false
        /** Last state handed by Compose; replayed when the style finishes loading after the last recomposition. */
        var latest: TrackMapState? = null
        private var lastTracks: List<List<MapPoint>>? = null
        private var lastMarkers: List<MapMarker>? = null
        private var lastFocus: MapPoint? = null
        var onClick: ((MapPoint) -> Unit)? = null
        private var lastCurrent: MapPoint? = null
        private var lastStops: List<MapPoint>? = null
        var userMoved = false

        fun attach(map: MapLibreMap, style: Style) {
            this.map = map
            trackSource = GeoJsonSource(SRC_TRACK).also { style.addSource(it) }
            pointSource = GeoJsonSource(SRC_POINTS).also { style.addSource(it) }
            style.addLayer(
                LineLayer(LAYER_TRACK, SRC_TRACK).withProperties(
                    PropertyFactory.lineColor("#1F3864"),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                CircleLayer(LAYER_STOPS, SRC_POINTS).withProperties(
                    PropertyFactory.circleColor("#F4B942"),
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor("#1B1B1B"),
                    PropertyFactory.circleStrokeWidth(1.5f),
                ).withFilter(org.maplibre.android.style.expressions.Expression.eq(org.maplibre.android.style.expressions.Expression.get("kind"), "stop")),
            )
            style.addLayer(
                CircleLayer(LAYER_MARKERS, SRC_POINTS).withProperties(
                    PropertyFactory.circleColor(
                        org.maplibre.android.style.expressions.Expression.match(
                            org.maplibre.android.style.expressions.Expression.get("kind"),
                            org.maplibre.android.style.expressions.Expression.rgb(31, 56, 100),
                            org.maplibre.android.style.expressions.Expression.stop("PHOTO", org.maplibre.android.style.expressions.Expression.rgb(0, 150, 136)),
                            org.maplibre.android.style.expressions.Expression.stop("VIDEO", org.maplibre.android.style.expressions.Expression.rgb(0, 121, 107)),
                            org.maplibre.android.style.expressions.Expression.stop("VOICE", org.maplibre.android.style.expressions.Expression.rgb(156, 39, 176)),
                            org.maplibre.android.style.expressions.Expression.stop("TEXT", org.maplibre.android.style.expressions.Expression.rgb(96, 125, 139)),
                            org.maplibre.android.style.expressions.Expression.stop("HIGHLIGHT", org.maplibre.android.style.expressions.Expression.rgb(255, 152, 0)),
                            org.maplibre.android.style.expressions.Expression.stop("PLACED", org.maplibre.android.style.expressions.Expression.rgb(211, 47, 47)),
                            org.maplibre.android.style.expressions.Expression.stop("PLACE", org.maplibre.android.style.expressions.Expression.rgb(121, 85, 72)),
                        ),
                    ),
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2f),
                ).withFilter(org.maplibre.android.style.expressions.Expression.eq(org.maplibre.android.style.expressions.Expression.get("layer"), "marker")),
            )
            style.addLayer(
                CircleLayer(LAYER_CURRENT, SRC_POINTS).withProperties(
                    PropertyFactory.circleColor("#1F3864"),
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                ).withFilter(org.maplibre.android.style.expressions.Expression.eq(org.maplibre.android.style.expressions.Expression.get("kind"), "current")),
            )
        }

        fun render(state: TrackMapState) {
            val m = map ?: return
            // The screen recomposes every second (elapsed time); only touch the GPU sources when the data changed (I11).
            if (state.tracks !== lastTracks) {
                lastTracks = state.tracks
                val lines = state.tracks.filter { it.size >= 2 }.map { t -> Feature.fromGeometry(LineString.fromLngLats(t.map { Point.fromLngLat(it.lon, it.lat) })) }
                trackSource?.setGeoJson(FeatureCollection.fromFeatures(lines))
            }
            if (state.current != lastCurrent || state.stops !== lastStops || state.markers !== lastMarkers) {
                lastCurrent = state.current
                lastStops = state.stops
                lastMarkers = state.markers
                val features = ArrayList<Feature>()
                state.stops.forEach { s -> features += Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).also { it.addStringProperty("kind", "stop") } }
                state.markers.forEach { m -> features += Feature.fromGeometry(Point.fromLngLat(m.point.lon, m.point.lat)).also { it.addStringProperty("layer", "marker"); it.addStringProperty("kind", m.kind.name); it.addStringProperty("id", m.id) } }
                state.current?.let { c -> features += Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).also { it.addStringProperty("kind", "current") } }
                pointSource?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
            if (state.focus != null && state.focus != lastFocus) {
                lastFocus = state.focus
                framedOnce = true
                m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(state.focus.lat, state.focus.lon), maxOf(m.cameraPosition.zoom, 15.0)), 600)
                return
            }

            val all = state.tracks.flatten()
            if (!framedOnce && all.size >= 2) {
                val b = LatLngBounds.Builder().also { bb -> all.forEach { bb.include(LatLng(it.lat, it.lon)) } }.build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 80))
                framedOnce = true
            } else if (state.follow && !userMoved && state.current != null) {
                val target = LatLng(state.current.lat, state.current.lon)
                if (!framedOnce) {
                    m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 16.0))
                    framedOnce = true
                } else {
                    m.easeCamera(CameraUpdateFactory.newLatLng(target), 500)
                }
            }
        }

        companion object {
            const val SRC_TRACK = "track"
            const val SRC_POINTS = "points"
            const val LAYER_TRACK = "track-line"
            const val LAYER_STOPS = "stops"
            const val LAYER_MARKERS = "markers"
            const val LAYER_CURRENT = "current"
        }
    }
}
