package com.bitacoraviaje.data

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt module for the persistence layer. The database binding lands in Phase 1. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule
