package com.bitacoraviaje.app.ui.common

import androidx.annotation.StringRes
import com.bitacoraviaje.app.R
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.SamplingProfile
import com.bitacoraviaje.core.model.Transport

@StringRes
fun profileLabelRes(p: SamplingProfile): Int = when (p) {
    SamplingProfile.TREKKING_HIGH -> R.string.profile_trekking_high
    SamplingProfile.WALK -> R.string.profile_walk
    SamplingProfile.BIKE -> R.string.profile_bike
    SamplingProfile.VEHICLE -> R.string.profile_vehicle
    SamplingProfile.PLANE -> R.string.profile_plane
    SamplingProfile.SAVER -> R.string.profile_saver
    SamplingProfile.ECONOMY -> R.string.profile_economy
    SamplingProfile.REST -> R.string.profile_rest
}

@StringRes
fun linkMethodLabelRes(m: LinkMethod): Int = when (m) {
    LinkMethod.NONE -> R.string.link_none
    LinkMethod.MANUAL -> R.string.link_manual
    LinkMethod.EXIF -> R.string.link_exif
    LinkMethod.TRACK_INTERPOLATED -> R.string.link_track_interpolated
    LinkMethod.TRACK_NEAREST -> R.string.link_track_nearest
}

@StringRes
fun transportLabelRes(t: Transport): Int = when (t) {
    Transport.WALK -> R.string.transport_walk
    Transport.BIKE -> R.string.transport_bike
    Transport.CAR -> R.string.transport_car
    Transport.BUS -> R.string.transport_bus
    Transport.TRAIN -> R.string.transport_train
    Transport.BOAT -> R.string.transport_boat
    Transport.PLANE -> R.string.transport_plane
    Transport.MIXED -> R.string.transport_mixed
}
