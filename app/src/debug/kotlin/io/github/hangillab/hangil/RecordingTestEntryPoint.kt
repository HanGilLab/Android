package io.github.hangillab.hangil

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository

/** Debug-only access to the real graph for opt-in instrumentation; no replacement dependencies. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingTestEntryPoint {
    fun sessions(): PracticeSessionRepository

    fun observations(): VehicleObservationRepository

    fun vehicle(): VehicleGateway

    fun preparation(): PracticeRepository

    fun memos(): SessionMemoRepository

    fun restrictions(): DrivingRestrictionsProvider
}
