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
    val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_AREA_INFORMATION,
    )

    /** Copies [input] to [tmp], removes the GPS tags in place and returns a stream of the clean copy (null if the file is not a JPEG ExifInterface can rewrite). */
    fun stripped(input: InputStream, tmp: File): InputStream? {
        tmp.outputStream().use { input.copyTo(it) }
        return try {
            val exif = ExifInterface(tmp)
            GPS_TAGS.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            tmp.inputStream()
        } catch (e: Exception) {
            null
        }
    }

    fun hasGps(file: File): Boolean = runCatching { ExifInterface(file).latLong != null }.getOrDefault(false)
}
