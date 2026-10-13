package com.bitacoraviaje.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Media access state (DISENO §7.4). PARTIAL only exists on Android 14+. */
enum class MediaAccess { FULL, PARTIAL, NONE }

@Singleton
class MediaPermissions @Inject constructor(@ApplicationContext private val context: Context) {
    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun access(): MediaAccess = when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
        else -> MediaAccess.NONE
    }

    fun hasMediaLocation(): Boolean = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    /** What to request, per API level (skill media-linking). */
    fun requestSet(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
