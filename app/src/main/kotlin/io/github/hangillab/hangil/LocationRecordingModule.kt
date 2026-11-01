package io.github.hangillab.hangil

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.ForegroundLocationRecordingController
import io.github.hangillab.hangil.core.domain.LocationRecordingController
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocationRecordingModule {
    @Provides @Singleton
    fun locationController(
        gateway: LocationUpdateGateway,
        sessions: PracticeSessionRepository,
        locationRepository: LocationRecordingRepository,
        vehicle: VehicleGateway,
        restrictions: DrivingRestrictionsProvider,
        clock: Clock,
    ): LocationRecordingController =
        ForegroundLocationRecordingController(
            gateway = gateway,
            sessions = sessions,
            locationRepository = locationRepository,
            vehicle = vehicle,
            restrictions = restrictions,
            clock = clock,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            nextRunId = { UUID.randomUUID().toString() },
            processId = UUID.randomUUID().toString(),
        )
}
