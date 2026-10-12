package com.bitacoraviaje.app

import androidx.exifinterface.media.ExifInterface
import com.bitacoraviaje.app.media.ExifData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The synthetic fixture comes from tools/sample-trips/make_photos.py (case p1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExifDataTest {
    @Test
    fun `synthetic jpeg exposes time, offset, gps and camera`() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/exif/p1_exif_gps_on_track.jpg"))
        val d = stream.use { ExifData.from(ExifInterface(it)) }
        assertNotNull(d.dateTimeOriginal)
        assertEquals("-03:00", d.offsetTimeOriginal)
        assertNotNull(d.lat, "GPS latitude must be parsed from the GPS IFD")
        assertNotNull(d.lon)
        assertEquals("Synthetic", d.make)
        assertEquals("CamA", d.model)
        assertNotNull(d.gpsDateStamp)
    }
}
