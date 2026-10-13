package com.bitacoraviaje.data.repository

import androidx.room.withTransaction
import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.mapper.toDomain
import com.bitacoraviaje.data.mapper.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Places, their internet texts and which segment visited them (DISENO §11). The cache the spec asks for is these tables. */
@Singleton
class PlacesRepository @Inject constructor(private val db: BitacoraDatabase) {
    private val places get() = db.placeDao()
    private val enrichments get() = db.enrichmentDao()
    private val links get() = db.segmentPlaceDao()

    suspend fun hasPlaces(segmentId: String): Boolean = links.countBySegment(segmentId) > 0

    suspend fun getPlace(id: String): Place? = places.getById(id)?.toDomain()

    suspend fun placesOf(segmentId: String): List<Place> = places.getBySegment(segmentId).map { it.toDomain() }

    suspend fun placesOfTrip(tripId: String): List<Place> = places.getByTrip(tripId).map { it.toDomain() }

    fun observePlacesOfTrip(tripId: String): Flow<List<Place>> = places.observeByTrip(tripId).map { l -> l.map { it.toDomain() } }

    suspend fun enrichmentsOf(placeId: String): List<Enrichment> = enrichments.getByPlace(placeId).map { it.toDomain() }

    suspend fun enrichmentsOfTrip(tripId: String): List<Enrichment> = enrichments.getByTrip(tripId).map { it.toDomain() }

    fun observeEnrichmentsOfTrip(tripId: String): Flow<List<Enrichment>> = enrichments.observeByTrip(tripId).map { l -> l.map { it.toDomain() } }

    suspend fun segmentPlacesOfTrip(tripId: String): List<SegmentPlace> = links.getByTrip(tripId).map { it.toDomain() }

    fun observeSegmentPlacesOfTrip(tripId: String): Flow<List<SegmentPlace>> = links.observeByTrip(tripId).map { l -> l.map { it.toDomain() } }

    /**
     * Replaces what a segment knows in one transaction: the places and texts are upserted
     * (shared with other segments), the segment's links are rewritten. Re-running the
     * enrichment never leaves a half-written segment.
     */
    suspend fun replaceSegmentPlaces(segmentId: String, newPlaces: List<Place>, newEnrichments: List<Enrichment>, newLinks: List<SegmentPlace>, stopPlaces: Map<String, String> = emptyMap()) = db.withTransaction {
        if (newPlaces.isNotEmpty()) places.upsertAll(newPlaces.map { it.toEntity() })
        // The stop → place link belongs to the same write: no dangling stop.place_id if interrupted (review M15).
        for ((stopId, placeId) in stopPlaces) db.stopDao().setPlace(stopId, placeId)
        // Keep the user's approvals and edits when the same text comes back (idempotence, DISENO §11.4).
        val existing = newEnrichments.map { it.placeId }.distinct().flatMap { enrichments.getByPlace(it) }.associateBy { it.id }
        val merged = newEnrichments.map { e ->
            val old = existing[e.id]
            when {
                old == null -> e
                // Approved, edited or rewritten: the text the user saw stays; a changed upstream text becomes the "original" to restore.
                old.userApproved || old.originalSummary != null || old.llmRewritten ->
                    e.copy(summary = old.summary, originalSummary = if (old.summary != e.summary) e.summary else old.originalSummary, userApproved = old.userApproved, llmRewritten = old.llmRewritten)
                else -> e
            }
        }
        if (merged.isNotEmpty()) enrichments.upsertAll(merged.map { it.toEntity() })
        val dismissed = links.dismissedPlaceIds(segmentId).toSet()
        links.deleteBySegment(segmentId)
        val live = newLinks.filter { it.placeId !in dismissed } // a discarded place does not come back (review I5)
        if (live.isNotEmpty()) links.upsertAll(live.map { it.toEntity() })
    }

    /** Import path: rows arrive already linked; nothing is deleted. */
    suspend fun insertImported(newPlaces: List<Place>, newEnrichments: List<Enrichment>, newLinks: List<SegmentPlace>) = db.withTransaction {
        if (newPlaces.isNotEmpty()) places.upsertAll(newPlaces.map { it.toEntity() })
        if (newEnrichments.isNotEmpty()) enrichments.upsertAll(newEnrichments.map { it.toEntity() })
        if (newLinks.isNotEmpty()) links.upsertAll(newLinks.map { it.toEntity() })
    }

    suspend fun setApproved(enrichmentId: String, approved: Boolean) = enrichments.setApproved(enrichmentId, approved)

    suspend fun editSummary(enrichmentId: String, summary: String) = enrichments.setSummary(enrichmentId, summary, rewritten = false)

    suspend fun setRewritten(enrichmentId: String, summary: String) = enrichments.setSummary(enrichmentId, summary, rewritten = true)

    suspend fun restoreSummary(enrichmentId: String) = enrichments.restoreSummary(enrichmentId)

    /** "Descartar": the place leaves this segment for good (tombstone); the cached rows stay for other segments. */
    suspend fun discard(segmentId: String, placeId: String) = links.dismiss(segmentId, placeId)

    suspend fun pruneOrphans(): Int = places.deleteOrphans()
}
