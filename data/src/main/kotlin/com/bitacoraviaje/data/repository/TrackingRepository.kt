package com.bitacoraviaje.data.repository

import androidx.room.withTransaction
import com.bitacoraviaje.core.geo.TimeInterval
import com.bitacoraviaje.core.geo.computeSegmentStats
import com.bitacoraviaje.core.geo.detectStops
import com.bitacoraviaje.core.geo.simplifyIndices
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SamplingProfile
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentPause
import com.bitacoraviaje.core.model.SegmentStats
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Stop
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.TripStatus
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.mapper.toDomain
import com.bitacoraviaje.data.mapper.toEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Injectable clock so repository logic is testable without waiting. */
fun interface Clock {
    fun nowUtcMs(): Long
}

/**
 * Writes for the recording flow. Every multi-row change is a transaction: the
 * "never lose a trip" rule starts here. Pure computations come from core:geo.
 */
@Singleton
class TrackingRepository @Inject constructor(
    private val db: BitacoraDatabase,
    private val clock: Clock,
) {
    private val trips get() = db.tripDao()
    private val segments get() = db.segmentDao()
    private val points get() = db.trackPointDao()
    private val stops get() = db.stopDao()

    fun observeOpenSegment(): Flow<Segment?> = segments.observeOpen().map { it?.toDomain() }

    fun observeActiveTrip(): Flow<Trip?> = trips.observeActive().map { it?.toDomain() }

    fun observeTrips(): Flow<List<Trip>> = trips.observeAll().map { l -> l.map { it.toDomain() } }

    suspend fun getOpenSegment(): Segment? = segments.getOpen()?.toDomain()

    suspend fun getSegment(id: String): Segment? = segments.getById(id)?.toDomain()

    fun observeSegments(tripId: String): Flow<List<Segment>> = segments.observeByTrip(tripId).map { l -> l.map { it.toDomain() } }

    /**
     * Starts a recording session. Creates the trip when there is no ACTIVE one
     * (TRIP_START); otherwise appends to the active trip. A nested excursion or trek
     * pauses the open parent segment with [PauseKind.NESTED].
     */
    suspend fun startSession(
        type: SegmentType,
        transport: Transport?,
        mode: RecordingMode,
        tzId: String,
        title: String? = null,
        intentText: String? = null,
        tripTitle: String? = null,
    ): Segment = db.withTransaction {
        val now = clock.nowUtcMs()
        val trip = trips.getActive()?.toDomain() ?: Trip(
            id = UUID.randomUUID().toString(),
            title = tripTitle ?: "Viaje",
            createdUtcMs = now,
            startedUtcMs = now,
            status = TripStatus.ACTIVE,
        ).also { trips.insert(it.toEntity()) }

        val open = segments.getOpen()?.toDomain()
        var parentId: String? = null
        if (open != null) {
            if (type == SegmentType.EXCURSION || type == SegmentType.TREKKING) {
                if (open.status == SegmentStatus.ACTIVE) pauseInternal(open, PauseKind.NESTED, now)
                parentId = open.id
            } else {
                finishInternal(open, now, SegmentStatus.FINISHED)
            }
        }
        val segment = Segment(
            id = UUID.randomUUID().toString(),
            tripId = trip.id,
            parentSegmentId = parentId,
            type = type,
            transport = transport,
            title = title,
            intentText = intentText,
            startedUtcMs = now,
            tzId = tzId,
            samplingProfile = SamplingProfile.forSession(type, transport, mode).name,
            recordingMode = mode,
            status = SegmentStatus.ACTIVE,
        )
        segments.insert(segment.toEntity())
        segment
    }

    suspend fun insertPoints(batch: List<TrackPoint>) {
        if (batch.isNotEmpty()) points.insertAll(batch.map { it.toEntity() })
    }

    suspend fun pause(segmentId: String, kind: PauseKind) = db.withTransaction {
        val s = segments.getById(segmentId)?.toDomain() ?: return@withTransaction
        if (s.status == SegmentStatus.ACTIVE) pauseInternal(s, kind, clock.nowUtcMs())
    }

    suspend fun resume(segmentId: String) = db.withTransaction {
        val s = segments.getById(segmentId)?.toDomain() ?: return@withTransaction
        if (s.status != SegmentStatus.PAUSED) return@withTransaction
        val now = clock.nowUtcMs()
        segments.getOpenPause(segmentId)?.let { segments.updatePause(it.copy(resumedUtcMs = now)) }
        segments.update(s.copy(status = SegmentStatus.ACTIVE).toEntity())
    }

    /**
     * Local midnight passed (adenda 1): close the daily segment and open the next one
     * with the same trip, transport, mode and time zone, flagged as auto-created.
     * Nested segments (excursions) are never cut: they end when the user says so.
     */
    suspend fun cutDay(segmentId: String): Segment? = db.withTransaction {
        val s = segments.getById(segmentId)?.toDomain() ?: return@withTransaction null
        if (s.parentSegmentId != null) return@withTransaction null
        val now = clock.nowUtcMs()
        val wasPaused = s.status == SegmentStatus.PAUSED
        val openPauseKind = segments.getOpenPause(s.id)?.kind
        finishInternal(s, now, SegmentStatus.FINISHED)
        val next = s.copy(
            id = UUID.randomUUID().toString(),
            type = SegmentType.STAGE,
            title = null,
            intentText = null,
            startedUtcMs = now,
            endedUtcMs = null,
            autoCreated = true,
            status = SegmentStatus.ACTIVE,
            stats = SegmentStats(),
        )
        segments.insert(next.toEntity())
        // Carry an automatic rest across midnight so the new day starts in the same state.
        if (wasPaused && openPauseKind == PauseKind.AUTO_REST.name) pauseInternal(next, PauseKind.AUTO_REST, now)
        next
    }

    /** Closes the segment: stats, stops and simplification are computed and stored atomically. */
    suspend fun finishSegment(segmentId: String, status: SegmentStatus = SegmentStatus.FINISHED): Segment? = db.withTransaction {
        val s = segments.getById(segmentId)?.toDomain() ?: return@withTransaction null
        finishInternal(s, clock.nowUtcMs(), status)
    }

    /** Ends the trip and any open segment. */
    suspend fun finishTrip(tripId: String) = db.withTransaction {
        val now = clock.nowUtcMs()
        segments.getOpen()?.toDomain()?.takeIf { it.tripId == tripId }?.let { finishInternal(it, now, SegmentStatus.FINISHED) }
        trips.getById(tripId)?.toDomain()?.let { trips.update(it.copy(status = TripStatus.FINISHED, endedUtcMs = now).toEntity()) }
    }

    suspend fun validPoints(segmentId: String): List<TrackPoint> = points.getValidBySegment(segmentId).map { it.toDomain() }

    suspend fun stopsOf(segmentId: String): List<Stop> = stops.getBySegment(segmentId).map { it.toDomain() }

    suspend fun pausesOf(segmentId: String): List<SegmentPause> = segments.getPauses(segmentId).map { it.toDomain() }

    private suspend fun pauseInternal(s: Segment, kind: PauseKind, now: Long) {
        segments.insertPause(SegmentPause(UUID.randomUUID().toString(), s.id, kind, now).toEntity())
        segments.update(s.copy(status = SegmentStatus.PAUSED).toEntity())
    }

    private suspend fun finishInternal(s: Segment, now: Long, status: SegmentStatus): Segment {
        segments.getOpenPause(s.id)?.let { segments.updatePause(it.copy(resumedUtcMs = now)) }
        val valid = points.getValidBySegment(s.id).map { it.toDomain() }
        val pauses = segments.getPauses(s.id).map { TimeInterval(it.pausedUtcMs, it.resumedUtcMs ?: now) }
        val stats = computeSegmentStats(valid, pauses, s.transport, totalTimeMs = now - s.startedUtcMs)
        val detected = detectStops(valid, minDurationMs = if (s.transport.isVehicle()) 10 * 60_000 else 5 * 60_000)
        stops.replaceForSegment(
            s.id,
            detected.map { Stop(UUID.randomUUID().toString(), s.id, it.startUtcMs, it.endUtcMs, it.lat, it.lon, it.radiusM).toEntity() },
        )
        if (valid.size > 2) {
            val keep = simplifyIndices(valid, 5.0).map { valid[it].id }
            points.markSimplified(s.id, keep)
        }
        val done = s.copy(status = status, endedUtcMs = now, stats = stats)
        segments.update(done.toEntity())
        return done
    }

    private fun Transport?.isVehicle() = this != null && this != Transport.WALK && this != Transport.BIKE
}
