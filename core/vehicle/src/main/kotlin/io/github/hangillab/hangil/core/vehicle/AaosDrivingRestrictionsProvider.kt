package io.github.hangillab.hangil.core.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.toDrivingRestrictions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AaosDrivingRestrictionsProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        vehicleGateway: VehicleGateway,
    ) : DrivingRestrictionsProvider {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        override val restrictions =
            combine(vehicleGateway.state, observeUxRestrictions()) { vehicle, ux ->
                vehicle.toDrivingRestrictions(ux)
            }.stateIn(
                scope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
                DrivingRestrictions(),
            )

        private fun observeUxRestrictions() =
            callbackFlow {
                trySend(UxRestrictionState.UNKNOWN)
                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                    awaitClose { }
                    return@callbackFlow
                }
                var active = true
                var generation = 0L
                var manager: CarUxRestrictionsManager? = null
                var car: Car? = null
                val handler = Handler(Looper.getMainLooper())

                fun reset() {
                    generation++
                    runCatching { manager?.unregisterListener() }
                    manager = null
                    trySend(UxRestrictionState.UNKNOWN)
                }

                try {
                    car =
                        Car.createCar(context, handler, 0L) { connectedCar, ready ->
                            if (active) {
                                reset()
                                if (ready) {
                                    try {
                                        val connectedManager =
                                            connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                                                as? CarUxRestrictionsManager
                                        if (connectedManager != null) {
                                            manager = connectedManager
                                            val token = generation
                                            connectedManager.registerListener { value ->
                                                if (active && token == generation) trySend(value.toUxState())
                                            }
                                            trySend(connectedManager.currentCarUxRestrictions.toUxState())
                                        }
                                    } catch (_: Exception) {
                                        reset()
                                    }
                                }
                            }
                        }
                } catch (_: Exception) {
                    reset()
                } catch (_: LinkageError) {
                    // android.car가 없는 환경에서도 잠금을 유지하며 앱은 실행 가능하다.
                    reset()
                }
                awaitClose {
                    active = false
                    generation++
                    runCatching { manager?.unregisterListener() }
                    runCatching { car?.disconnect() }
                }
            }.buffer(Channel.CONFLATED)

        private fun CarUxRestrictions?.toUxState(): UxRestrictionState =
            when {
                this == null -> UxRestrictionState.UNKNOWN
                !isRequiresDistractionOptimization && activeRestrictions == CarUxRestrictions.UX_RESTRICTIONS_BASELINE ->
                    UxRestrictionState.UNRESTRICTED
                else -> UxRestrictionState.RESTRICTED
            }
    }
