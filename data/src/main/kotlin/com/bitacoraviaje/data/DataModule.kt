package com.bitacoraviaje.data

import android.content.Context
import androidx.room.Room
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.db.dao.SegmentDao
import com.bitacoraviaje.data.db.dao.StopDao
import com.bitacoraviaje.data.db.dao.TrackPointDao
import com.bitacoraviaje.data.db.dao.TripDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt module for the persistence layer. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BitacoraDatabase =
        Room.databaseBuilder(context, BitacoraDatabase::class.java, BitacoraDatabase.NAME)
            // WAL is Room's default on API 16+; made explicit because the spec relies on it (§3).
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*BitacoraDatabase.ALL_MIGRATIONS)
            // No fallbackToDestructiveMigration: a missing migration must fail loudly, never wipe a trip.
            .build()

    @Provides fun provideTripDao(db: BitacoraDatabase): TripDao = db.tripDao()

    @Provides fun provideSegmentDao(db: BitacoraDatabase): SegmentDao = db.segmentDao()

    @Provides fun provideTrackPointDao(db: BitacoraDatabase): TrackPointDao = db.trackPointDao()

    @Provides fun provideStopDao(db: BitacoraDatabase): StopDao = db.stopDao()

    @Provides fun provideMediaDao(db: BitacoraDatabase): com.bitacoraviaje.data.db.dao.MediaDao = db.mediaDao()

    @Provides fun provideVoiceNoteDao(db: BitacoraDatabase): com.bitacoraviaje.data.db.dao.VoiceNoteDao = db.voiceNoteDao()

    @Provides fun provideTextNoteDao(db: BitacoraDatabase): com.bitacoraviaje.data.db.dao.TextNoteDao = db.textNoteDao()

    @Provides fun providePlaceDao(db: BitacoraDatabase): com.bitacoraviaje.data.db.dao.PlaceDao = db.placeDao()

    @Provides fun provideEnrichmentDao(db: BitacoraDatabase): com.bitacoraviaje.data.db.dao.EnrichmentDao = db.enrichmentDao()

    @Provides fun provideSegmentPlaceDao(db: BitacoraDatabase): com.bitacoraviaje.data.db.dao.SegmentPlaceDao = db.segmentPlaceDao()
}

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @Singleton
    fun provideClock(): com.bitacoraviaje.data.repository.Clock = com.bitacoraviaje.data.repository.Clock { System.currentTimeMillis() }
}
