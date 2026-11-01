package io.github.hangillab.hangil.core.database

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
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
    abstract fun nextPracticeRepository(implementation: RoomNextPracticeRepository): NextPracticeRepository

    @Binds @Singleton
    abstract fun sessionRepository(implementation: RoomPracticeSessionRepository): PracticeSessionRepository

    @Binds @Singleton
    abstract fun vehicleObservationRepository(implementation: RoomVehicleObservationRepository): VehicleObservationRepository

    @Binds @Singleton
    abstract fun sessionMemoRepository(implementation: RoomSessionMemoRepository): SessionMemoRepository

    @Binds @Singleton
    abstract fun historySessionMemoWriter(implementation: RoomHistorySessionMemoWriter): HistorySessionMemoWriter

    @Binds @Singleton
    abstract fun recordingRunRepository(implementation: RoomRecordingRunRepository): RecordingRunRepository

    @Binds @Singleton
    abstract fun locationRecordingRepository(implementation: RoomLocationRecordingRepository): LocationRecordingRepository

    @Binds @Singleton
    abstract fun courseCompletionRepository(implementation: RoomCourseCompletionRepository): CourseCompletionRepository

    @Binds @Singleton
    abstract fun sessionDeletionRepository(implementation: RoomSessionDeletionRepository): SessionDeletionRepository
}
