package com.bitacoraviaje.app.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.data.repository.TrackingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Use-case façade for the UI: starts/stops the service and keeps the database consistent. */
@Singleton
class TrackingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TrackingRepository,
    private val stateHolder: TrackingStateHolder,
) {
    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Must be called while the app is visible (foreground service start rules). */
    suspend fun startSession(type: SegmentType, transport: Transport?, mode: RecordingMode, title: String? = null, intentText: String? = null): String {
        check(hasLocationPermission()) { "location permission required" }
        val segment = repository.startSession(type, transport, mode, TimeZone.getDefault().id, title, intentText)
        ContextCompat.startForegroundService(
            context,
            Intent(context, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_SEGMENT_ID, segment.id)
                .putExtra(TrackingService.EXTRA_PROFILE, segment.samplingProfile),
        )
        return segment.id
    }

    fun pause() = send(TrackingService.ACTION_PAUSE)

    fun resume() = send(TrackingService.ACTION_RESUME)

    /** Stops the service (which flushes its buffer) and closes the segment with stats. */
    suspend fun finishSegment(segmentId: String) {
        send(TrackingService.ACTION_STOP)
        repository.finishSegment(segmentId, SegmentStatus.FINISHED)
        stateHolder.reset()
    }

    suspend fun finishTrip(tripId: String) {
        send(TrackingService.ACTION_STOP)
        repository.finishTrip(tripId)
        stateHolder.reset()
    }

    suspend fun pauseInDb(segmentId: String, kind: PauseKind) = repository.pause(segmentId, kind)

    private fun send(action: String) {
        context.startService(Intent(context, TrackingService::class.java).setAction(action))
    }
}
