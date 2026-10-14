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

    /** Backlog D3: the weather provider behind the enricher. */
    @Binds
    abstract fun bindWeather(impl: com.bitacoraviaje.app.weather.OpenMeteoClient): com.bitacoraviaje.app.weather.WeatherSource
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    @Binds
    abstract fun bindRewriter(impl: ClaudeRewriter): LlmRewriter

    @Binds
    abstract fun bindSecrets(impl: com.bitacoraviaje.app.settings.KeystoreSecretStore): com.bitacoraviaje.app.settings.Secrets
}
