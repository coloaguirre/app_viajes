package com.bitacoraviaje.app.enrichment

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The enricher depends on [WikipediaSource]; swapping the Wikimedia API behind it is one line here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnrichmentModule {
    @Binds
    abstract fun bindWikipedia(impl: WikipediaClient): WikipediaSource
}
