package io.github.hangillab.hangil.core.database

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PracticeBindings {
    @Binds @Singleton
    abstract fun catalog(implementation: LocalPracticeCatalog): PracticeCatalog

    @Binds @Singleton
    abstract fun repository(implementation: RoomPracticeRepository): PracticeRepository
}
