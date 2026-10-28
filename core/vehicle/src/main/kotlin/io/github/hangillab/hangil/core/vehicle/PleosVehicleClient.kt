package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleReading
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** 조회 API만 사용한다. SDK 인스턴스는 구독 수명마다 새로 생성하고 해제한다. */
@Suppress("DEPRECATION") // Vehicle 2.3.2의 공개 Gear 조회 API를 사용한다.
internal class PleosVehicleClient(
    private val context: Context,
) : VehicleClient {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private lateinit var observer: VehicleClient.Observer
    private var gearRegistered = false
    private var turnRegistered = false
    private var brakeRegistered = false
    private val turnRevision = AtomicLong()
    private val brakeRevision = AtomicLong()

    @Volatile private var closed = false

    private val gearListener =
        object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = deliver { observer.onGear(gear.toReading()) }

            override fun onFailed(e: Exception) = deliver { observer.onGear(e.toReadingFailure()) }
        }

    private val turnListener =
        object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) =
                deliver {
                    turnRevision.incrementAndGet()
                    observer.onTurnSignal(state.toReading())
                }

            override fun onFailed(e: Exception) =
                deliver {
                    turnRevision.incrementAndGet()
                    observer.onTurnSignal(e.toReadingFailure())
                }
        }

    private val brakeListener =
        object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) =
                deliver {
                    brakeRevision.incrementAndGet()
                    observer.onBrake(status?.let { VehicleReading.Available(it) } ?: VehicleReading.Unknown)
                }

            override fun onFailed(e: Exception) =
                deliver {
                    brakeRevision.incrementAndGet()
                    observer.onBrake(e.toReadingFailure())
                }
        }

    override fun open(observer: VehicleClient.Observer) {
        this.observer = observer
        val manager = context.packageManager
        if (!manager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            manager.queryIntentServices(Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0).isEmpty()
        ) {
            throw UnsupportedOperationException()
        }

        if (!hasPermission(DRIVE_PERMISSION)) throw SecurityException()
        val sdk = Vehicle(context)
        vehicle = sdk
        sdk.initialize()
        sdk.getGear().registerGearSelection(gearListener)
        gearRegistered = true
        observeBrake(sdk)
        if (hasPermission(LIGHTS_PERMISSION)) {
            observeTurnSignal(sdk)
        } else {
            observer.onTurnSignal(VehicleReading.PermissionDenied)
        }
    }

    private fun observeTurnSignal(sdk: Vehicle) {
        val signal = sdk.getTurnSignal()
        signal.checkTurnSignalStateCapabilities(
            onComplete = { capability ->
                deliver {
                    if (capability.accessType !in READABLE_ACCESS) {
                        observer.onTurnSignal(VehicleReading.Unsupported)
                    } else {
                        signal.registerTurnSignalState(turnListener)
                        turnRegistered = true
                        val revision = turnRevision.get()
                        signal.getCurrentTurnSignalState(
                            onComplete = { value ->
                                deliver {
                                    if (revision == turnRevision.get()) observer.onTurnSignal(value.toReading())
                                }
                            },
                            onFailed = { exception ->
                                deliver {
                                    if (revision == turnRevision.get()) observer.onTurnSignal(exception.toReadingFailure())
                                }
                            },
                        )
                    }
                }
            },
            onFailed = { exception -> deliver { observer.onTurnSignal(exception.toReadingFailure()) } },
        )
    }

    private fun observeBrake(sdk: Vehicle) {
        val brake = sdk.getBrake()
        brake.checkBrakePedalStatusCapabilities(
            onComplete = { capability ->
                deliver {
                    if (capability.accessType !in READABLE_ACCESS) {
                        observer.onBrake(VehicleReading.Unsupported)
                    } else {
                        brake.registerBrakePedalStatus(brakeListener)
                        brakeRegistered = true
                        val revision = brakeRevision.get()
                        brake.getCurrentBrakePedalStatus(
                            onComplete = { value ->
                                deliver {
                                    if (revision == brakeRevision.get()) {
                                        observer.onBrake(value?.let { VehicleReading.Available(it) } ?: VehicleReading.Unknown)
                                    }
                                }
                            },
                            onFailed = { exception ->
                                deliver {
                                    if (revision == brakeRevision.get()) observer.onBrake(exception.toReadingFailure())
                                }
                            },
                        )
                    }
                }
            },
            onFailed = { exception -> deliver { observer.onBrake(exception.toReadingFailure()) } },
        )
    }

    override suspend fun readGear(): VehicleReading<GearPosition> {
        if (!hasPermission(DRIVE_PERMISSION) || !hasPermission(LIGHTS_PERMISSION)) return VehicleReading.PermissionDenied
        val sdk = vehicle ?: return VehicleReading.Unknown
        return suspendCancellableCoroutine { continuation ->
            sdk.getGear().getCurrentGearSelectionState(
                onComplete = { value ->
                    if (continuation.isActive) continuation.resume(value.toReading())
                },
                onFailed = { exception ->
                    if (continuation.isActive) continuation.resume(exception.toReadingFailure())
                },
            )
        }
    }

    private fun hasPermission(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun deliver(block: () -> Unit) {
        // 등록과 해제, 초기 조회/콜백의 순서를 메인 스레드에서 직렬화한다.
        mainHandler.post {
            if (!closed) {
                try {
                    block()
                } catch (exception: Exception) {
                    observer.onGear(exception.toReadingFailure())
                }
            }
        }
    }

    override fun close() {
        closed = true
        val sdk = vehicle ?: return
        if (gearRegistered) runCatching { sdk.getGear().unregisterGearSelection(gearListener) }
        if (turnRegistered) runCatching { sdk.getTurnSignal().unregisterTurnSignalState(turnListener) }
        if (brakeRegistered) runCatching { sdk.getBrake().unregisterBrakePedalStatus(brakeListener) }
        runCatching { sdk.release() }
        vehicle = null
    }

    private fun VehicleGear?.toReading(): VehicleReading<GearPosition> =
        when (this) {
            null, VehicleGear.GEAR_UNKNOWN -> VehicleReading.Unknown
            VehicleGear.GEAR_PARK -> VehicleReading.Available(GearPosition.PARK)
            VehicleGear.GEAR_REVERSE -> VehicleReading.Available(GearPosition.REVERSE)
            VehicleGear.GEAR_NEUTRAL -> VehicleReading.Available(GearPosition.NEUTRAL)
            VehicleGear.GEAR_DRIVE -> VehicleReading.Available(GearPosition.DRIVE)
            else -> VehicleReading.Available(GearPosition.OTHER)
        }

    private fun TurnSignalValue?.toReading(): VehicleReading<TurnSignalState> =
        when (this) {
            null -> VehicleReading.Unknown
            TurnSignalValue.OFF -> VehicleReading.Available(TurnSignalState.OFF)
            TurnSignalValue.LEFT -> VehicleReading.Available(TurnSignalState.LEFT)
            TurnSignalValue.RIGHT -> VehicleReading.Available(TurnSignalState.RIGHT)
        }

    private companion object {
        const val DRIVE_PERMISSION = "pleos.car.permission.CAR_DRIVE"
        const val LIGHTS_PERMISSION = "pleos.car.permission.CAR_LIGHTS"
        val READABLE_ACCESS = setOf(AccessType.READ, AccessType.READ_WRITE)
    }
}
