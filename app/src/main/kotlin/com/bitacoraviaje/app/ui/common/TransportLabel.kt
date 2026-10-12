package com.bitacoraviaje.app.ui.common

import androidx.annotation.StringRes
import com.bitacoraviaje.app.R
import com.bitacoraviaje.core.model.Transport

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
