package io.github.hangillab.hangil.core.vehicle

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PleosVehicleGateway
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : VehicleGateway {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        override val state =
            VehicleMonitor({ PleosVehicleClient(context) }).observe().stateIn(
                scope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
                VehicleState(),
            )
    }
