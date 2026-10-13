package com.bitacoraviaje.data.mapper

import com.bitacoraviaje.core.model.BoundingBox
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
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
import com.bitacoraviaje.data.db.entity.MediaItemEntity
import com.bitacoraviaje.data.db.entity.SegmentEntity
import com.bitacoraviaje.data.db.entity.SegmentPauseEntity
import com.bitacoraviaje.data.db.entity.StopEntity
import com.bitacoraviaje.data.db.entity.TextNoteEntity
import com.bitacoraviaje.data.db.entity.VoiceNoteEntity
import com.bitacoraviaje.data.db.entity.TrackPointEntity
import com.bitacoraviaje.data.db.entity.TripEntity

/*
 * Entity <-> domain mapping. Enums travel as names. An unknown name in the database
 * is corruption or a newer schema: we fail loudly (IllegalArgumentException from
 * valueOf) instead of silently classifying garbage.
 */

fun TripEntity.toDomain() = Trip(
    id = id, title = title, description = description, createdUtcMs = createdUtcMs,
    startedUtcMs = startedUtcMs, endedUtcMs = endedUtcMs, coverMediaId = coverMediaId,
    status = TripStatus.valueOf(status),
)

fun Trip.toEntity() = TripEntity(
    id = id, title = title, description = description, createdUtcMs = createdUtcMs,
    startedUtcMs = startedUtcMs, endedUtcMs = endedUtcMs, coverMediaId = coverMediaId,
    status = status.name,
)

fun SegmentEntity.toDomain(): Segment {
    val bbox = if (bboxMinLat != null && bboxMinLon != null && bboxMaxLat != null && bboxMaxLon != null) {
        BoundingBox(bboxMinLat, bboxMinLon, bboxMaxLat, bboxMaxLon)
    } else {
        null
    }
    return Segment(
        id = id, tripId = tripId, parentSegmentId = parentSegmentId,
        type = SegmentType.valueOf(type), transport = transport?.let(Transport::valueOf),
        title = title, intentText = intentText, autoName = autoName, startedUtcMs = startedUtcMs, endedUtcMs = endedUtcMs,
        tzId = tzId, samplingProfile = samplingProfile, recordingMode = RecordingMode.valueOf(recordingMode),
        autoCreated = autoCreated, status = SegmentStatus.valueOf(status),
        stats = SegmentStats(
            distanceM = distanceM, movingTimeMs = movingTimeMs, totalTimeMs = totalTimeMs,
            maxSpeedMps = maxSpeedMps, avgMovingSpeedMps = avgMovingSpeedMps,
            elevationGainM = elevationGainM, elevationLossM = elevationLossM,
            minAltM = minAltM, maxAltM = maxAltM, bbox = bbox,
        ),
    )
}

fun Segment.toEntity() = SegmentEntity(
    id = id, tripId = tripId, parentSegmentId = parentSegmentId, type = type.name,
    transport = transport?.name, title = title, intentText = intentText, autoName = autoName,
    startedUtcMs = startedUtcMs, endedUtcMs = endedUtcMs, tzId = tzId,
    samplingProfile = samplingProfile, recordingMode = recordingMode.name,
    autoCreated = autoCreated, status = status.name,
    distanceM = stats.distanceM, movingTimeMs = stats.movingTimeMs, totalTimeMs = stats.totalTimeMs,
    maxSpeedMps = stats.maxSpeedMps, avgMovingSpeedMps = stats.avgMovingSpeedMps,
    elevationGainM = stats.elevationGainM, elevationLossM = stats.elevationLossM,
    minAltM = stats.minAltM, maxAltM = stats.maxAltM,
    bboxMinLat = stats.bbox?.minLat, bboxMinLon = stats.bbox?.minLon,
    bboxMaxLat = stats.bbox?.maxLat, bboxMaxLon = stats.bbox?.maxLon,
)

fun SegmentPauseEntity.toDomain() =
    SegmentPause(id = id, segmentId = segmentId, kind = PauseKind.valueOf(kind), pausedUtcMs = pausedUtcMs, resumedUtcMs = resumedUtcMs)

fun SegmentPause.toEntity() =
    SegmentPauseEntity(id = id, segmentId = segmentId, kind = kind.name, pausedUtcMs = pausedUtcMs, resumedUtcMs = resumedUtcMs)

fun TrackPointEntity.toDomain() = TrackPoint(
    id = id, segmentId = segmentId, timeUtcMs = timeUtcMs, lat = lat, lon = lon, altM = altM, altMslM = altMslM,
    hAccuracyM = hAccuracyM, vAccuracyM = vAccuracyM, speedMps = speedMps, speedAccuracyMps = speedAccuracyMps,
    bearingDeg = bearingDeg, provider = provider, activity = activity, isMock = isMock, monotonicMs = monotonicMs,
    isFilteredOut = isFilteredOut, isSimplifiedKeep = isSimplifiedKeep,
)

fun TrackPoint.toEntity() = TrackPointEntity(
    id = id, segmentId = segmentId, timeUtcMs = timeUtcMs, lat = lat, lon = lon, altM = altM, altMslM = altMslM,
    hAccuracyM = hAccuracyM, vAccuracyM = vAccuracyM, speedMps = speedMps, speedAccuracyMps = speedAccuracyMps,
    bearingDeg = bearingDeg, provider = provider, activity = activity, isMock = isMock, monotonicMs = monotonicMs,
    isFilteredOut = isFilteredOut, isSimplifiedKeep = isSimplifiedKeep,
)

fun MediaItemEntity.toDomain() = MediaItem(
    id = id, tripId = tripId, segmentId = segmentId, kind = MediaKind.valueOf(kind), source = MediaSource.valueOf(source),
    contentUri = contentUri, mediaStoreId = mediaStoreId, displayName = displayName, mimeType = mimeType, sizeBytes = sizeBytes,
    width = width, height = height, durationMs = durationMs, orientationDeg = orientationDeg,
    takenUtcMs = takenUtcMs, takenTimeSource = TakenTimeSource.valueOf(takenTimeSource),
    exifLat = exifLat, exifLon = exifLon, cameraMake = cameraMake, cameraModel = cameraModel,
    linkedLat = linkedLat, linkedLon = linkedLon, linkMethod = LinkMethod.valueOf(linkMethod), linkConfidence = linkConfidence,
    linkTimeGapMs = linkTimeGapMs, needsReview = needsReview, sha256 = sha256, userCaption = userCaption,
    isFavorite = isFavorite, isExcluded = isExcluded,
)

fun MediaItem.toEntity() = MediaItemEntity(
    id = id, tripId = tripId, segmentId = segmentId, kind = kind.name, source = source.name,
    contentUri = contentUri, mediaStoreId = mediaStoreId, displayName = displayName, mimeType = mimeType, sizeBytes = sizeBytes,
    width = width, height = height, durationMs = durationMs, orientationDeg = orientationDeg,
    takenUtcMs = takenUtcMs, takenTimeSource = takenTimeSource.name,
    exifLat = exifLat, exifLon = exifLon, cameraMake = cameraMake, cameraModel = cameraModel,
    linkedLat = linkedLat, linkedLon = linkedLon, linkMethod = linkMethod.name, linkConfidence = linkConfidence,
    linkTimeGapMs = linkTimeGapMs, needsReview = needsReview, sha256 = sha256, userCaption = userCaption,
    isFavorite = isFavorite, isExcluded = isExcluded,
)

fun VoiceNoteEntity.toDomain() = VoiceNote(
    id = id, segmentId = segmentId, startedUtcMs = startedUtcMs, durationMs = durationMs, lat = lat, lon = lon, hAccuracyM = hAccuracyM,
    audioPath = audioPath, audioFormat = audioFormat, transcript = transcript, transcriptEdited = transcriptEdited, transcriptLang = transcriptLang,
    transcriptEngine = transcriptEngine, transcriptStatus = TranscriptStatus.valueOf(transcriptStatus), transcriptSegmentsJson = transcriptSegmentsJson,
    mediaItemId = mediaItemId,
)

fun VoiceNote.toEntity() = VoiceNoteEntity(
    id = id, segmentId = segmentId, startedUtcMs = startedUtcMs, durationMs = durationMs, lat = lat, lon = lon, hAccuracyM = hAccuracyM,
    audioPath = audioPath, audioFormat = audioFormat, transcript = transcript, transcriptEdited = transcriptEdited, transcriptLang = transcriptLang,
    transcriptEngine = transcriptEngine, transcriptStatus = transcriptStatus.name, transcriptSegmentsJson = transcriptSegmentsJson,
    mediaItemId = mediaItemId,
)

fun TextNoteEntity.toDomain() = TextNote(id = id, segmentId = segmentId, timeUtcMs = timeUtcMs, lat = lat, lon = lon, kind = TextNoteKind.valueOf(kind), text = text)

fun TextNote.toEntity() = TextNoteEntity(id = id, segmentId = segmentId, timeUtcMs = timeUtcMs, lat = lat, lon = lon, kind = kind.name, text = text)

fun StopEntity.toDomain() = Stop(
    id = id, segmentId = segmentId, startUtcMs = startUtcMs, endUtcMs = endUtcMs,
    lat = lat, lon = lon, radiusM = radiusM, placeId = placeId, userLabel = userLabel,
)

fun Stop.toEntity() = StopEntity(
    id = id, segmentId = segmentId, startUtcMs = startUtcMs, endUtcMs = endUtcMs,
    lat = lat, lon = lon, radiusM = radiusM, placeId = placeId, userLabel = userLabel,
)

fun com.bitacoraviaje.data.db.entity.PlaceEntity.toDomain() = com.bitacoraviaje.core.model.Place(
    id = id, name = name, lat = lat, lon = lon, kind = kind, countryCode = countryCode, admin1 = admin1, admin2 = admin2, locality = locality,
    osmType = osmType, osmId = osmId, wikidataId = wikidataId, wikipediaTitle = wikipediaTitle, wikipediaLang = wikipediaLang,
    source = com.bitacoraviaje.core.model.PlaceSource.valueOf(source), fetchedUtcMs = fetchedUtcMs,
)

fun com.bitacoraviaje.core.model.Place.toEntity() = com.bitacoraviaje.data.db.entity.PlaceEntity(
    id = id, name = name, lat = lat, lon = lon, kind = kind, countryCode = countryCode, admin1 = admin1, admin2 = admin2, locality = locality,
    osmType = osmType, osmId = osmId, wikidataId = wikidataId, wikipediaTitle = wikipediaTitle, wikipediaLang = wikipediaLang, source = source.name, fetchedUtcMs = fetchedUtcMs,
)

fun com.bitacoraviaje.data.db.entity.EnrichmentEntity.toDomain() = com.bitacoraviaje.core.model.Enrichment(
    id = id, placeId = placeId, lang = lang, title = title, summary = summary, sourceName = sourceName, sourceUrl = sourceUrl, license = license,
    thumbnailUrl = thumbnailUrl, originalSummary = originalSummary, llmRewritten = llmRewritten, userApproved = userApproved, fetchedUtcMs = fetchedUtcMs,
)

fun com.bitacoraviaje.core.model.Enrichment.toEntity() = com.bitacoraviaje.data.db.entity.EnrichmentEntity(
    id = id, placeId = placeId, lang = lang, title = title, summary = summary, sourceName = sourceName, sourceUrl = sourceUrl, license = license,
    thumbnailUrl = thumbnailUrl, originalSummary = originalSummary, llmRewritten = llmRewritten, userApproved = userApproved, fetchedUtcMs = fetchedUtcMs,
)

fun com.bitacoraviaje.data.db.entity.SegmentPlaceEntity.toDomain() = com.bitacoraviaje.core.model.SegmentPlace(segmentId, placeId, firstSeenUtcMs, dwellMs, relevance, dismissed)

fun com.bitacoraviaje.core.model.SegmentPlace.toEntity() = com.bitacoraviaje.data.db.entity.SegmentPlaceEntity(segmentId, placeId, firstSeenUtcMs, dwellMs, relevance, dismissed)
