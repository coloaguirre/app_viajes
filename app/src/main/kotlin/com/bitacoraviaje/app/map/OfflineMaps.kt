package com.bitacoraviaje.app.map

import android.content.Context
import android.util.Log
import com.bitacoraviaje.core.geo.TileMath
import com.bitacoraviaje.core.model.BoundingBox
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** What the app stores next to a MapLibre offline region (its definition has only bounds and zooms). */
@Serializable
data class OfflineRegionMeta(val name: String, val created_utc_ms: Long, val trip_id: String? = null) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(m: OfflineRegionMeta): ByteArray = json.encodeToString(serializer(), m).toByteArray(Charsets.UTF_8)
        fun decode(bytes: ByteArray?): OfflineRegionMeta? = bytes?.let { runCatching { json.decodeFromString(serializer(), it.toString(Charsets.UTF_8)) }.getOrNull() }
    }
}

data class OfflineRegionInfo(
    val id: Long,
    val name: String,
    val tripId: String?,
    val bounds: BoundingBox,
    val minZoom: Int,
    val maxZoom: Int,
    val completedTiles: Long,
    val requiredTiles: Long,
    val sizeBytes: Long,
    val complete: Boolean,
    val createdUtcMs: Long,
)

/** Progress of one download; [done] with [error] == null means every tile is on the phone. */
data class OfflineProgress(val completedTiles: Long, val requiredTiles: Long, val sizeBytes: Long, val done: Boolean = false, val error: String? = null, val region: Int = 1, val regions: Int = 1)

/**
 * PLAN 5.4 / DISENO §5.3: offline regions through MapLibre's `OfflineManager`. A region is the
 * style plus every tile of a bounding box between two zooms; once downloaded, the same style URL
 * renders from the local cache with no network. Regions stay until the user removes them.
 */
@Singleton
class OfflineMaps @Inject constructor(@ApplicationContext private val context: Context, private val renderer: MapLibreRenderer) {
    private val manager: OfflineManager by lazy {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context).also { it.setOfflineMapboxTileCountLimit(TILE_LIMIT) }
    }

    private val _regions = MutableStateFlow<List<OfflineRegionInfo>?>(null)

    /** Null until the first [refresh]; then the regions on the phone, newest first. */
    val regions: StateFlow<List<OfflineRegionInfo>?> = _regions

    val isAvailable: Boolean get() = renderer.isConfigured

    suspend fun refresh(): List<OfflineRegionInfo> {
        val list = listRegions().map { info(it) }.sortedByDescending { it.createdUtcMs }
        _regions.value = list
        return list
    }

    private suspend fun listRegions(): List<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) { if (cont.isActive) cont.resume(offlineRegions?.toList() ?: emptyList()) }
            override fun onError(error: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
        })
    }

    private suspend fun status(region: OfflineRegion): OfflineRegionStatus? = suspendCancellableCoroutine { cont ->
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { if (cont.isActive) cont.resume(status) }
            override fun onError(error: String?) { if (cont.isActive) cont.resume(null) }
        })
    }

    private suspend fun info(region: OfflineRegion): OfflineRegionInfo {
        val def = region.definition as? OfflineTilePyramidRegionDefinition
        val meta = OfflineRegionMeta.decode(region.metadata)
        val st = status(region)
        val b = def?.bounds
        return OfflineRegionInfo(
            id = region.id,
            name = meta?.name ?: "Región ${region.id}",
            tripId = meta?.trip_id,
            bounds = if (b != null) BoundingBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast) else BoundingBox(0.0, 0.0, 0.0, 0.0),
            minZoom = def?.minZoom?.toInt() ?: 0,
            maxZoom = def?.maxZoom?.toInt() ?: 0,
            completedTiles = st?.completedTileCount ?: 0,
            requiredTiles = st?.requiredResourceCount ?: 0,
            sizeBytes = st?.completedResourceSize ?: 0,
            complete = st?.isComplete ?: false,
            createdUtcMs = meta?.created_utc_ms ?: 0,
        )
    }

    /**
     * Downloads [bounds] between [minZoom] and [maxZoom] as a named region and reports progress until
     * every tile is stored (or the SDK's tile limit / a network error stops it). Cancelling the
     * collector pauses the download; the region stays listed as partial until removed.
     */
    fun download(name: String, tripId: String?, bounds: BoundingBox, minZoom: Int, maxZoom: Int): Flow<OfflineProgress> = callbackFlow {
        val definition = OfflineTilePyramidRegionDefinition(
            renderer.styleUrl,
            LatLngBounds.from(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon),
            minZoom.toDouble(),
            maxZoom.toDouble(),
            context.resources.displayMetrics.density,
        )
        var active: OfflineRegion? = null
        manager.createOfflineRegion(
            definition,
            OfflineRegionMeta.encode(OfflineRegionMeta(name, System.currentTimeMillis(), tripId)),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    active = offlineRegion
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val p = OfflineProgress(status.completedTileCount, status.requiredResourceCount, status.completedResourceSize, done = status.isComplete)
                            trySend(p)
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                Log.i(TAG, "offline region ready id=${offlineRegion.id} tiles=${status.completedTileCount} bytes=${status.completedResourceSize}")
                                close()
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            Log.w(TAG, "offline region error: ${error.reason} ${error.message}")
                            trySend(OfflineProgress(0, 0, 0, done = true, error = "${error.reason}: ${error.message}"))
                            close()
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            Log.w(TAG, "offline tile limit exceeded: $limit")
                            trySend(OfflineProgress(0, 0, 0, done = true, error = "tile_limit:$limit"))
                            close()
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    Log.w(TAG, "offline region create failed: $error")
                    trySend(OfflineProgress(0, 0, 0, done = true, error = error))
                    close()
                }
            },
        )
        awaitClose {
            active?.setObserver(null)
            active?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        }
    }

    suspend fun delete(id: Long): Boolean {
        val region = listRegions().firstOrNull { it.id == id } ?: return false
        val ok = suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { if (cont.isActive) cont.resume(true) }
                override fun onError(error: String) { Log.w(TAG, "offline region delete failed: $error"); if (cont.isActive) cont.resume(false) }
            })
        }
        refresh()
        return ok
    }

    companion object {
        private const val TAG = "Bitacora"

        /** MapLibre's default ceiling per region; the dialog caps the zoom so a region never trips it. */
        const val TILE_LIMIT = 6_000L
        const val MIN_ZOOM = 8
        const val PADDING_KM = 3.0

        /** The two detail levels offered (DISENO: "descargar una región antes del viaje"): streets, or trails. */
        val DETAIL_ZOOMS = listOf(12, 14)

        /** What a trip's region would cost at [maxZoom] (pure; used by the dialog and its test). */
        fun estimate(bounds: BoundingBox, maxZoom: Int): Pair<Long, Long> {
            val tiles = TileMath.tileCount(bounds, MIN_ZOOM, maxZoom)
            return tiles to TileMath.estimateBytes(tiles)
        }

        fun regionBounds(points: BoundingBox): BoundingBox = TileMath.pad(points, PADDING_KM)

        private fun union(a: BoundingBox, b: BoundingBox) = BoundingBox(minOf(a.minLat, b.minLat), minOf(a.minLon, b.minLon), maxOf(a.maxLat, b.maxLat), maxOf(a.maxLon, b.maxLon))

        /**
         * Regions to download for a trip whose stages may be far apart (Buenos Aires + Patagonia): one
         * padded box when everything fits the tile limit at [maxZoom]; otherwise each stage's box, merged
         * greedily with its neighbours while the union still fits. Null when a single stage is too big.
         */
        fun plan(stageBoxes: List<BoundingBox>, maxZoom: Int): List<BoundingBox>? {
            if (stageBoxes.isEmpty()) return emptyList()
            fun fits(b: BoundingBox) = TileMath.tileCount(b, MIN_ZOOM, maxZoom) <= TILE_LIMIT
            val whole = regionBounds(stageBoxes.reduce(::union))
            if (fits(whole)) return listOf(whole)
            val out = ArrayList<BoundingBox>()
            for (box in stageBoxes.map { regionBounds(it) }) {
                if (!fits(box)) return null
                val i = out.indexOfFirst { fits(union(it, box)) }
                if (i >= 0) out[i] = union(out[i], box) else out += box
            }
            return out
        }

        /** Tiles and bytes for a whole plan. */
        fun estimate(plan: List<BoundingBox>, maxZoom: Int): Pair<Long, Long> {
            val tiles = plan.sumOf { TileMath.tileCount(it, MIN_ZOOM, maxZoom) }
            return tiles to TileMath.estimateBytes(tiles)
        }
    }
}
