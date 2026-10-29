package io.github.hangillab.hangil.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): PreparationDatabase =
        Room
            .databaseBuilder(context, PreparationDatabase::class.java, "hangil.db")
            .addMigrations(PreparationMigration1To2, PreparationMigration2To3)
            .build()
}
