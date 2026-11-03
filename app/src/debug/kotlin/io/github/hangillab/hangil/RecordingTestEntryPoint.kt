package io.github.hangillab.hangil

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.RecordingController
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository

/** Debug-only access to the real graph for opt-in instrumentation; no replacement dependencies. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingTestEntryPoint {
    fun locationUpdates(): LocationUpdateGateway

    fun locationRecordings(): LocationRecordingRepository

    fun sessions(): PracticeSessionRepository

    fun observations(): VehicleObservationRepository

    fun recording(): RecordingController

    fun recordingRuns(): RecordingRunRepository

    fun vehicle(): VehicleGateway

    fun preparation(): PracticeRepository

    fun nextPractice(): NextPracticeRepository

    fun catalog(): PracticeCatalog

    fun memos(): SessionMemoRepository

    fun historyMemoWriter(): HistorySessionMemoWriter

    fun courseCompletions(): CourseCompletionRepository

    fun restrictions(): DrivingRestrictionsProvider
}
