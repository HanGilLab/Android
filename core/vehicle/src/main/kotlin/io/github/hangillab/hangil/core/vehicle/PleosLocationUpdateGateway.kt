package io.github.hangillab.hangil.core.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PleosLocationUpdateGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LocationUpdateGateway {
        override fun hasPermission(): Boolean =
            context.checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED

        override fun updates(
            canContinue: () -> Boolean,
            onRegistered: () -> Unit,
            onCleanupFailure: (Throwable) -> Unit,
        ): Flow<LocationUpdateReading> =
            LocationUpdateOperation(
                clientFactory = { PleosLocationUpdateClient(context) },
                mainDispatcher = Dispatchers.Main.immediate,
                clock = SystemClock::elapsedRealtime,
            ).updates(canContinue, onRegistered, onCleanupFailure)
    }
