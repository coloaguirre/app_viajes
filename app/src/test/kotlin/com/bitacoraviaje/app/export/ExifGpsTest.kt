package com.bitacoraviaje.app.export

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 5.5: the exported JPEG copy loses its GPS tags; the source file keeps them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExifGpsTest {
    @Test
    fun `strip removes the gps tags from the copy and leaves the original alone`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = File(ctx.cacheDir, "with-gps.jpg")
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, src.outputStream())
        ExifInterface(src).apply { setLatLong(-34.6, -58.4); setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:13 10:00:00"); saveAttributes() }
        assertTrue(ExifGps.hasGps(src))
        val tmp = File(ctx.cacheDir, "stripped.jpg")
        val stream = assertNotNull(ExifGps.stripped(src.inputStream(), tmp))
        stream.close()
        assertNull(ExifInterface(tmp).latLong, "copy without GPS")
        ExifGps.GPS_TAGS.forEach { assertNull(ExifInterface(tmp).getAttribute(it), "$it removed from the copy (review M2)") }
        assertEquals("2026:10:13 10:00:00", ExifInterface(tmp).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), "other tags survive")
        assertTrue(ExifGps.hasGps(src), "the original is untouched")
        assertNull(ExifGps.stripped("not a jpeg".byteInputStream(), File(ctx.cacheDir, "bad.jpg")), "a non-JPEG is reported, not corrupted")
    }
}
