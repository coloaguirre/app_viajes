package com.bitacoraviaje.app.media

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun bindMediaCatalog(impl: MediaScanner): MediaCatalog
}
