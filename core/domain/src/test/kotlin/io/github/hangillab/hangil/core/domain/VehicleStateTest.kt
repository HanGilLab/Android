package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStateTest {
    @Test
    fun `only connected park with confirmed unrestricted UX unlocks details`() {
        for (connection in ConnectionState.entries) {
            for (gear in GearPosition.entries) {
                for (ux in UxRestrictionState.entries) {
                    val vehicle = VehicleState(connection, VehicleReading.Available(gear))
                    assertEquals(
                        "$connection / $gear / $ux",
                        connection == ConnectionState.CONNECTED && gear == GearPosition.PARK && ux == UxRestrictionState.UNRESTRICTED,
                        vehicle.toDrivingRestrictions(ux).allowsDetailedInteraction,
                    )
                }
            }
        }
    }

    @Test
    fun `missing unsupported denied and failed gear never become parking`() {
        val readings = listOf(VehicleReading.Unknown, VehicleReading.Unsupported, VehicleReading.PermissionDenied, VehicleReading.Error)
        for (reading in readings) {
            val restrictions = VehicleState(ConnectionState.CONNECTED, reading).toDrivingRestrictions(UxRestrictionState.UNRESTRICTED)
            assertEquals(ParkingState.UNKNOWN, restrictions.parkingState)
            assertFalse(restrictions.allowsDetailedInteraction)
        }
    }

    @Test
    fun `stale park value does not unlock after service disconnects`() {
        val parked = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
        assertTrue(parked.toDrivingRestrictions(UxRestrictionState.UNRESTRICTED).allowsDetailedInteraction)
        val disconnected = parked.copy(connection = ConnectionState.DISCONNECTED)
        assertFalse(disconnected.toDrivingRestrictions(UxRestrictionState.UNRESTRICTED).allowsDetailedInteraction)
    }
}
