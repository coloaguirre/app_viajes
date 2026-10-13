package com.bitacoraviaje.core.geo

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/** Broad class of a place: it sets how far from the trip an article may be and still count (DISENO §11.3). */
enum class PlaceClass(val maxDistM: Double) {
    /** City, town, village: 5 km. */
    LOCALITY(5_000.0),
    /** Peak, lake, glacier, park: 10 km. */
    NATURAL(10_000.0),
    /** Museum, viewpoint, monument: 300 m. */
    POI(300.0),
}

/** A place proposed by one of the sources, with what the trip knows about it. */
data class PlaceCandidate(
    /** Deduplication key: the Wikidata QID when known, else source id. */
    val key: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val placeClass: PlaceClass,
    /** Distance from the nearest trip anchor (stop, sample, photo) in metres. */
    val distM: Double,
    /** Time spent at the anchor that found it (a stop's dwell), 0 for a drive-by. */
    val dwellMs: Long = 0,
    val hasPhotos: Boolean = false,
    val hasNotes: Boolean = false,
    /** Languages with an article (Wikidata sitelinks): notoriety. */
    val sitelinks: Int = 0,
)

data class RankedPlace(val candidate: PlaceCandidate, val score: Double)

/**
 * DISENO §11.3 / skill place-enrichment: `score = w1·dwell_norm + w2·hasPhotos + w3·hasNotes + w4·log(1+sitelinks) − w5·dist_norm`.
 * Pure: the weights are constants here, the inputs come from the database and the APIs.
 */
object PlaceRanking {
    const val W_DWELL = 2.0
    const val W_PHOTOS = 1.5
    const val W_NOTES = 1.0
    const val W_SITELINKS = 0.5
    const val W_DIST = 1.0
    const val DEFAULT_LIMIT = 5

    /** Drops candidates farther than their class allows, dedupes by key (closest wins), scores and keeps the top [limit]. */
    fun rank(candidates: List<PlaceCandidate>, limit: Int = DEFAULT_LIMIT): List<RankedPlace> {
        val inRange = candidates.filter { it.distM <= it.placeClass.maxDistM }
        val deduped = inRange.groupBy { it.key }.map { (_, same) ->
            // Several anchors may find the same article: keep the closest, merge what the others knew.
            val best = same.minByOrNull { it.distM }!!
            best.copy(
                dwellMs = same.maxOf { it.dwellMs },
                hasPhotos = same.any { it.hasPhotos },
                hasNotes = same.any { it.hasNotes },
                sitelinks = same.maxOf { it.sitelinks },
            )
        }
        if (deduped.isEmpty()) return emptyList()
        val maxDwell = deduped.maxOf { it.dwellMs }.coerceAtLeast(1L).toDouble()
        return deduped.map { c ->
            val dwellNorm = c.dwellMs / maxDwell
            val distNorm = c.distM / c.placeClass.maxDistM
            val score = W_DWELL * dwellNorm + W_PHOTOS * (if (c.hasPhotos) 1.0 else 0.0) + W_NOTES * (if (c.hasNotes) 1.0 else 0.0) +
                W_SITELINKS * ln(1.0 + c.sitelinks) - W_DIST * distNorm
            RankedPlace(c, score)
        }.sortedWith(compareByDescending<RankedPlace> { it.score }.thenBy { it.candidate.distM }).take(limit.coerceAtLeast(0))
    }
}

/** Privacy option (DISENO §15): coordinates leave the phone rounded to [decimals] (2 ≈ 1 km, 4 ≈ 10 m). */
object CoordinateRounding {
    fun round(value: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return (value * f).roundToLong() / f
    }
}
