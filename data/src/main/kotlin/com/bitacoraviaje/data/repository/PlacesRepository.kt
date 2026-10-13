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
    suspend fun replaceSegmentPlaces(segmentId: String, newPlaces: List<Place>, newEnrichments: List<Enrichment>, newLinks: List<SegmentPlace>) = db.withTransaction {
        if (newPlaces.isNotEmpty()) places.upsertAll(newPlaces.map { it.toEntity() })
        // Keep the user's approvals and edits when the same text comes back (idempotence, DISENO §11.4).
        val existing = newEnrichments.map { it.placeId }.distinct().flatMap { enrichments.getByPlace(it) }.associateBy { it.id }
        val merged = newEnrichments.map { e ->
            val old = existing[e.id]
            if (old == null) e else e.copy(summary = if (old.llmRewritten || old.summary != e.summary && old.userApproved) old.summary else e.summary, userApproved = old.userApproved, llmRewritten = old.llmRewritten)
        }
        if (merged.isNotEmpty()) enrichments.upsertAll(merged.map { it.toEntity() })
        links.deleteBySegment(segmentId)
        if (newLinks.isNotEmpty()) links.upsertAll(newLinks.map { it.toEntity() })
    }

    /** Import path: rows arrive already linked; nothing is deleted. */
    suspend fun insertImported(newPlaces: List<Place>, newEnrichments: List<Enrichment>, newLinks: List<SegmentPlace>) = db.withTransaction {
        if (newPlaces.isNotEmpty()) places.upsertAll(newPlaces.map { it.toEntity() })
        if (newEnrichments.isNotEmpty()) enrichments.upsertAll(newEnrichments.map { it.toEntity() })
        if (newLinks.isNotEmpty()) links.upsertAll(newLinks.map { it.toEntity() })
    }

    suspend fun setApproved(enrichmentId: String, approved: Boolean) = enrichments.setApproved(enrichmentId, approved)

    suspend fun editSummary(enrichmentId: String, summary: String) = enrichments.setSummary(enrichmentId, summary)

    /** "Descartar": the place leaves this segment; the cached rows stay for other segments or a re-run. */
    suspend fun discard(segmentId: String, placeId: String) = links.delete(segmentId, placeId)

    suspend fun pruneOrphans(): Int = places.deleteOrphans()
}
