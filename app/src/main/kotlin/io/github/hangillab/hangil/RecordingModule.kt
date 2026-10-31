package io.github.hangillab.hangil

import android.content.Context
import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeRecordingController
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.RecordingController
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecordingModule {
    @Provides @Singleton
    fun controller(
        @ApplicationContext context: Context,
        vehicle: VehicleGateway,
        sessions: PracticeSessionRepository,
        observations: VehicleObservationRepository,
        restrictions: DrivingRestrictionsProvider,
        runs: RecordingRunRepository,
        clock: Clock,
        host: VehicleRecordingServiceHost,
    ): RecordingController =
        PracticeRecordingController(
            vehicle,
            sessions,
            observations,
            restrictions,
            runs,
            clock,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            { UUID.randomUUID().toString() },
            UUID.randomUUID().toString(),
            { runId, sessionId ->
                context.startForegroundService(
                    Intent(context, VehicleRecordingService::class.java)
                        .putExtra(VehicleRecordingService.EXTRA_RUN, runId)
                        .putExtra(VehicleRecordingService.EXTRA_SESSION, sessionId),
                )
                Unit
            },
            host::stop,
        )
}
