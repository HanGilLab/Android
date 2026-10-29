package io.github.hangillab.hangil.core.database

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PracticeBindings {
    @Binds @Singleton
    abstract fun catalog(implementation: LocalPracticeCatalog): PracticeCatalog

    @Binds @Singleton
    abstract fun repository(implementation: RoomPracticeRepository): PracticeRepository

    @Binds @Singleton
    abstract fun sessionRepository(implementation: RoomPracticeSessionRepository): PracticeSessionRepository

    @Binds @Singleton
    abstract fun vehicleObservationRepository(implementation: RoomVehicleObservationRepository): VehicleObservationRepository
}
