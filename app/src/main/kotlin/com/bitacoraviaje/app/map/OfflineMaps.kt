package com.bitacoraviaje.app.map

import android.content.Context
import android.util.Log
import com.bitacoraviaje.app.BuildConfig
import com.bitacoraviaje.app.R
import com.bitacoraviaje.core.geo.TileMath
import com.bitacoraviaje.core.model.BoundingBox
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** What the app stores next to a MapLibre offline region (its definition has only bounds and zooms). */
@Serializable
data class OfflineRegionMeta(
    val name: String,
    @SerialName("created_utc_ms") val createdUtcMs: Long,
    @SerialName("trip_id") val tripId: String? = null,
) {
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
    /** Resources (tiles, style, sprites, glyphs) done and required: the only pair the SDK reports in the same unit. */
    val completedResources: Long,
    val requiredResources: Long,
    /** Bytes the SDK reports for this region; shared style assets are counted in every region (review M5). */
    val sizeBytes: Long,
    val complete: Boolean,
    val createdUtcMs: Long,
)

/**
 * Progress of the plan being downloaded. [fraction] is the current region's resources done over
 * required (null while the SDK's count is not precise yet); tiles and bytes accumulate over the
 * regions already finished. [done] with [error] == null means every region is on the phone.
 */
data class OfflineProgress(
    val tripId: String?,
    val completedTiles: Long = 0,
    val sizeBytes: Long = 0,
    val fraction: Float? = null,
    val done: Boolean = false,
    val error: String? = null,
    val region: Int = 1,
    val regions: Int = 1,
)

/**
 * PLAN 5.4 / DISENO §5.3: offline regions through MapLibre's `OfflineManager`. A region is the
 * style plus every tile of a bounding box between two zooms; once downloaded, the same style URL
 * renders from the local cache with no network. Regions stay until the user removes them. The
 * download runs in this singleton's own scope, so leaving the trip screen does not stop it
 * (review I3 of PR #15); a partial region (cancelled, app killed) is reused and resumed instead
 * of duplicated (review I2).
 */
@Singleton
class OfflineMaps @Inject constructor(@ApplicationContext private val context: Context, private val renderer: MapLibreRenderer) {
    private val manager: OfflineManager by lazy {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _regions = MutableStateFlow<List<OfflineRegionInfo>?>(null)

    /** Null until the first [refresh]; then the regions on the phone, newest first. */
    val regions: StateFlow<List<OfflineRegionInfo>?> = _regions

    private val _active = MutableStateFlow<OfflineProgress?>(null)

    /** The download in flight (or its final state until [clearResult]); null when idle. */
    val active: StateFlow<OfflineProgress?> = _active

    val isAvailable: Boolean get() = renderer.isConfigured

    suspend fun refresh(): List<OfflineRegionInfo> {
        val list = listRegions().map { info(it) }.sortedByDescending { it.createdUtcMs }
        _regions.value = list
        return list
    }

    private suspend fun listRegions(): List<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) { if (cont.isActive) cont.resume(offlineRegions?.toList() ?: emptyList()) }
            override fun onError(error: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException(sanitize(error))) }
        })
    }

    private suspend fun status(region: OfflineRegion): OfflineRegionStatus? = suspendCancellableCoroutine { cont ->
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { if (cont.isActive) cont.resume(status) }
            override fun onError(error: String?) { if (cont.isActive) cont.resume(null) }
        })
    }

    private fun boundsOf(region: OfflineRegion): BoundingBox? =
        (region.definition as? OfflineTilePyramidRegionDefinition)?.bounds?.let { BoundingBox(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast) }

    private suspend fun info(region: OfflineRegion): OfflineRegionInfo {
        val def = region.definition as? OfflineTilePyramidRegionDefinition
        val meta = OfflineRegionMeta.decode(region.metadata)
        val st = status(region)
        return OfflineRegionInfo(
            id = region.id,
            name = meta?.name ?: context.getString(R.string.offline_region_unnamed, region.id),
            tripId = meta?.tripId,
            bounds = boundsOf(region) ?: BoundingBox(0.0, 0.0, 0.0, 0.0),
            minZoom = def?.minZoom?.toInt() ?: 0,
            maxZoom = def?.maxZoom?.toInt() ?: 0,
            completedTiles = st?.completedTileCount ?: 0,
            completedResources = st?.completedResourceCount ?: 0,
            requiredResources = st?.requiredResourceCount ?: 0,
            sizeBytes = st?.completedResourceSize ?: 0,
            complete = st?.isComplete ?: false,
            createdUtcMs = meta?.createdUtcMs ?: 0,
        )
    }

    /**
     * Downloads every box of [plan] for [tripId] in sequence, reusing an incomplete region with the
     * same trip, box and zoom when one exists. Only one plan runs at a time; a new call while one
     * runs is ignored. Progress is published on [active].
     */
    fun startPlan(tripId: String, tripTitle: String, plan: List<BoundingBox>, minZoom: Int, maxZoom: Int) {
        if (job?.isActive == true || plan.isEmpty()) return
        _active.value = OfflineProgress(tripId, regions = plan.size)
        job = scope.launch {
            var tiles = 0L
            var bytes = 0L
            var failed: String? = null
            for ((i, box) in plan.withIndex()) {
                val name = if (plan.size > 1) context.getString(R.string.offline_region_name_zone, tripTitle, i + 1) else tripTitle
                var lastTiles = 0L
                var lastBytes = 0L
                var error: String? = null
                regionFlow(name, tripId, box, minZoom, maxZoom).collect { s ->
                    lastTiles = s.tiles
                    lastBytes = s.bytes
                    error = s.error
                    _active.value = OfflineProgress(tripId, tiles + s.tiles, bytes + s.bytes, s.fraction, region = i + 1, regions = plan.size)
                }
                if (error != null) { failed = error; break }
                tiles += lastTiles
                bytes += lastBytes
            }
            _active.value = OfflineProgress(tripId, tiles, bytes, 1f, done = true, error = failed, region = plan.size, regions = plan.size)
            runCatching { refresh() }
        }
    }

    /** Re-activates an incomplete region from Settings ("Reanudar"). */
    fun resume(regionId: Long) {
        if (job?.isActive == true) return
        job = scope.launch {
            val region = listRegions().firstOrNull { it.id == regionId } ?: return@launch
            val meta = OfflineRegionMeta.decode(region.metadata)
            _active.value = OfflineProgress(meta?.tripId)
            var error: String? = null
            var tiles = 0L
            var bytes = 0L
            observe(region).collect { s -> tiles = s.tiles; bytes = s.bytes; error = s.error; _active.value = OfflineProgress(meta?.tripId, s.tiles, s.bytes, s.fraction) }
            _active.value = OfflineProgress(meta?.tripId, tiles, bytes, 1f, done = true, error = error)
            runCatching { refresh() }
        }
    }

    /** Stops the running plan; the current region stays on the phone as incomplete, resumable from Settings. */
    fun cancel() {
        Log.i(TAG, "offline download cancelled by the user")
        job?.cancel()
        job = null
        _active.value = null
    }

    /** Clears a finished result so the dialog returns to its first state. */
    fun clearResult() { if (job?.isActive != true) _active.value = null }

    private data class RegionStatus(val tiles: Long, val bytes: Long, val fraction: Float?, val error: String? = null)

    /** One region: an existing incomplete twin is resumed, otherwise a new one is created. */
    private fun regionFlow(name: String, tripId: String, bounds: BoundingBox, minZoom: Int, maxZoom: Int): Flow<RegionStatus> = callbackFlow {
        val twins = runCatching { listRegions() }.getOrDefault(emptyList()).filter { r ->
            val def = r.definition as? OfflineTilePyramidRegionDefinition
            val meta = OfflineRegionMeta.decode(r.metadata)
            meta?.tripId == tripId && def != null && def.maxZoom.toInt() == maxZoom && boundsOf(r)?.let { same(it, bounds) } == true
        }
        val done = twins.firstNotNullOfOrNull { r -> status(r)?.takeIf { it.isComplete }?.let { r to it } }
        if (done != null) {
            // Same trip, box and zoom already on the phone: nothing to download, nothing to duplicate.
            Log.i(TAG, "offline region already complete id=${done.first.id}")
            send(RegionStatus(done.second.completedTileCount, done.second.completedResourceSize, 1f))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val twin = twins.firstOrNull()
        if (twin != null) {
            Log.i(TAG, "offline region resumed id=${twin.id}")
            observe(twin).collect { send(it) }
            close()
            awaitClose { }
            return@callbackFlow
        }
        val definition = OfflineTilePyramidRegionDefinition(
            renderer.styleUrl,
            LatLngBounds.from(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon),
            minZoom.toDouble(),
            maxZoom.toDouble(),
            context.resources.displayMetrics.density,
        )
        var created: OfflineRegion? = null
        manager.createOfflineRegion(
            definition,
            OfflineRegionMeta.encode(OfflineRegionMeta(name, System.currentTimeMillis(), tripId)),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    if (isClosedForSend) {
                        // Cancelled while the region was being created (review M2): nothing must keep downloading unobserved.
                        offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback { override fun onDelete() {} override fun onError(error: String) {} })
                        return
                    }
                    created = offlineRegion
                    launch { observe(offlineRegion).collect { send(it) }; close() }
                }

                override fun onError(error: String) {
                    Log.w(TAG, "offline region create failed: ${sanitize(error)}")
                    trySend(RegionStatus(0, 0, null, sanitize(error)))
                    close()
                }
            },
        )
        awaitClose { created?.setObserver(null); created?.setDownloadState(OfflineRegion.STATE_INACTIVE) }
    }.buffer(Channel.CONFLATED)

    /** Drives [region] until it is complete or fails; cancelling leaves it inactive and partial. */
    private fun observe(region: OfflineRegion): Flow<RegionStatus> = callbackFlow {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val fraction = if (status.isRequiredResourceCountPrecise && status.requiredResourceCount > 0) (status.completedResourceCount.toFloat() / status.requiredResourceCount).coerceIn(0f, 1f) else null
                trySend(RegionStatus(status.completedTileCount, status.completedResourceSize, fraction))
                // The required count is an estimate until the sources are enumerated (review M4).
                if (status.isComplete && status.isRequiredResourceCountPrecise) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    Log.i(TAG, "offline region ready id=${region.id} tiles=${status.completedTileCount} bytes=${status.completedResourceSize}")
                    close()
                }
            }

            override fun onError(error: OfflineRegionError) {
                // Per-resource errors are retried by the SDK while the region stays active; only a server refusal
                // (an invalid key, a quota) ends the download (review M1).
                Log.w(TAG, "offline region error: ${error.reason} ${sanitize(error.message)}")
                if (error.reason == OfflineRegionError.REASON_SERVER) {
                    trySend(RegionStatus(0, 0, null, sanitize(error.message)))
                    close()
                }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Log.w(TAG, "offline tile limit exceeded: $limit")
                trySend(RegionStatus(0, 0, null, "tile_limit:$limit"))
                close()
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        awaitClose {
            region.setObserver(null)
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            Log.i(TAG, "offline region paused id=${region.id}")
        }
    }.buffer(Channel.CONFLATED)

    suspend fun delete(id: Long): Boolean {
        val region = listRegions().firstOrNull { it.id == id } ?: return false
        val ok = suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { if (cont.isActive) cont.resume(true) }
                override fun onError(error: String) { Log.w(TAG, "offline region delete failed: ${sanitize(error)}"); if (cont.isActive) cont.resume(false) }
            })
        }
        refresh()
        return ok
    }

    companion object {
        private const val TAG = "Bitacora"

        /**
         * The app's own ceiling per region (about 140 MB at the measured 20 KB/tile): the dialog caps
         * the zoom so a region never exceeds it. MapLibre's "Mapbox tile count limit" governs only
         * `mapbox://` tiles, so it does not apply to MapTiler (review I4 of PR #15).
         */
        const val TILE_LIMIT = 6_000L
        const val MIN_ZOOM = 8
        const val PADDING_KM = 3.0

        /** The two detail levels offered (DISENO: "descargar una región antes del viaje"): streets, or trails. */
        val DETAIL_ZOOMS = listOf(12, 14)

        /** Never let the style URL (it carries the MapTiler key) reach a log or the screen (review M9). */
        fun sanitize(message: String?): String = (message ?: "").let { if (BuildConfig.MAPTILER_KEY.isNotBlank()) it.replace(BuildConfig.MAPTILER_KEY, "***") else it }

        private fun same(a: BoundingBox, b: BoundingBox, eps: Double = 1e-6) =
            abs(a.minLat - b.minLat) < eps && abs(a.minLon - b.minLon) < eps && abs(a.maxLat - b.maxLat) < eps && abs(a.maxLon - b.maxLon) < eps

        fun regionBounds(points: BoundingBox): BoundingBox = TileMath.pad(points, PADDING_KM)

        private fun union(a: BoundingBox, b: BoundingBox) = BoundingBox(minOf(a.minLat, b.minLat), minOf(a.minLon, b.minLon), maxOf(a.maxLat, b.maxLat), maxOf(a.maxLon, b.maxLon))

        /** The padded box around every stage of a trip. */
        fun wholeBounds(stageBoxes: List<BoundingBox>): BoundingBox? = stageBoxes.takeIf { it.isNotEmpty() }?.let { regionBounds(it.reduce(::union)) }

        /**
         * Regions to download for a trip whose stages may be far apart (Buenos Aires + Patagonia): one
         * padded box when everything fits [TILE_LIMIT] at [maxZoom]; otherwise each stage's box, merged
         * greedily with the first earlier group whose union still fits. Null when a single stage is too big.
         */
        fun plan(stageBoxes: List<BoundingBox>, maxZoom: Int): List<BoundingBox>? {
            if (stageBoxes.isEmpty()) return emptyList()
            fun fits(b: BoundingBox) = TileMath.tileCount(b, MIN_ZOOM, maxZoom) <= TILE_LIMIT
            val whole = wholeBounds(stageBoxes)!!
            if (fits(whole)) return listOf(whole)
            val out = ArrayList<BoundingBox>()
            for (box in stageBoxes.map { regionBounds(it) }) {
                if (!fits(box)) return null
                val i = out.indexOfFirst { fits(union(it, box)) }
                if (i >= 0) out[i] = union(out[i], box) else out += box
            }
            return out
        }

        /** Tiles and bytes for a whole plan (style assets once, then the tiles). */
        fun estimate(plan: List<BoundingBox>, maxZoom: Int): Pair<Long, Long> {
            val tiles = plan.sumOf { TileMath.tileCount(it, MIN_ZOOM, maxZoom) }
            return tiles to TileMath.estimateBytes(tiles)
        }

        /** Kept for callers that size a single box. */
        fun estimate(bounds: BoundingBox, maxZoom: Int): Pair<Long, Long> = estimate(listOf(bounds), maxZoom)
    }
}
