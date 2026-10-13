package com.bitacoraviaje.app.media

import android.Manifest
import android.app.Application
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

/** Review I4: on API 26–28 the provider has no RELATIVE_PATH / IS_PENDING columns; the scan must still work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaScannerCompatTest {
    @Test
    fun `queryByTime on API 28 reads rows with the legacy columns and filters by absolute path`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val scanner = MediaScanner(app, MediaPermissions(app))
        @Suppress("DEPRECATION")
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DATA, MediaScanner.COLUMN_ORIENTATION,
        )
        val images = RoboCursor().apply {
            setColumnNames(cols.toList())
            setResults(
                arrayOf(
                    arrayOf<Any>(1L, 1_000L, 1L, 1L, "a.jpg", "image/jpeg", 10L, 10, 10, "/storage/emulated/0/DCIM/Camera/a.jpg", 90),
                    arrayOf<Any>(2L, 2_000L, 2L, 2L, "shot.png", "image/png", 10L, 10, 10, "/storage/emulated/0/Pictures/Screenshots/shot.png", 0),
                ),
            )
        }
        @Suppress("DEPRECATION")
        Shadows.shadowOf(app.contentResolver).setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, images)
        val out = scanner.queryByTime(0, 10_000)
        assertEquals(listOf(1L), out.map { it.mediaStoreId }, "screenshot excluded by absolute path")
        assertEquals(90, out.single().orientationDeg)
    }
}
