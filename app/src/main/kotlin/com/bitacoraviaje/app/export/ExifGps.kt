package com.bitacoraviaje.app.export

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * PLAN 5.5: the exported JPEG copy of a photo inside a privacy zone loses its GPS tags. The
 * original in the gallery is never touched (rule: the user's media is read-only); the copy is
 * rewritten in a temp file and streamed from there.
 */
object ExifGps {
    /** Every GPS IFD tag ExifInterface knows (review M2 of PR #12): the whole IFD goes, not just lat/lon. */
    val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )

    /**
     * Copies [input] to [tmp] (closing [input]), removes the GPS tags in place and returns a stream of
     * the clean copy. Null, with [tmp] deleted, if the file is not a JPEG ExifInterface can rewrite or
     * if a position still reads back after saving: the caller then copies the original and reports it.
     */
    fun stripped(input: InputStream, tmp: File): InputStream? {
        input.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        return try {
            val exif = ExifInterface(tmp)
            GPS_TAGS.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            if (hasGps(tmp)) throw IllegalStateException("gps still present")
            tmp.inputStream()
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    fun hasGps(file: File): Boolean = runCatching { ExifInterface(file).latLong != null }.getOrDefault(false)
}
