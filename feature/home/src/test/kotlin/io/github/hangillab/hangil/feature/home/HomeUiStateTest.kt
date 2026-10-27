package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun `stale provider parking cannot unlock after a new drive or disconnect event`() {
        val state =
            HomeUiState(
                vehicle = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)),
                restrictions = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED),
            )
        assertTrue(state.allowsDetailedInteraction)
        assertFalse(state.copy(vehicle = state.vehicle.copy(gear = VehicleReading.Available(GearPosition.DRIVE))).allowsDetailedInteraction)
        assertFalse(state.copy(vehicle = state.vehicle.copy(connection = ConnectionState.DISCONNECTED)).allowsDetailedInteraction)
        assertFalse(state.copy(restrictions = DrivingRestrictions()).allowsDetailedInteraction)
        assertFalse(HomeUiState().allowsDetailedInteraction)
    }
}
