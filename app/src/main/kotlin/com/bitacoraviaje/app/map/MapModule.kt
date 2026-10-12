package com.bitacoraviaje.app.map

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MapModule {
    @Binds
    abstract fun bindMapRenderer(impl: MapLibreRenderer): MapRenderer
}
