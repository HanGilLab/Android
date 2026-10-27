package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleReading

internal interface VehicleClient {
    interface Observer {
        fun onGear(value: VehicleReading<GearPosition>)

        fun onTurnSignal(value: VehicleReading<TurnSignalState>)

        fun onBrake(value: VehicleReading<Boolean>)
    }

    fun open(observer: Observer)

    suspend fun readGear(): VehicleReading<GearPosition>

    fun close()
}
