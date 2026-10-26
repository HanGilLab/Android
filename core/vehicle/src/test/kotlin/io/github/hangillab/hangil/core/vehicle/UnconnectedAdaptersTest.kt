package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnconnectedAdaptersTest {
    @Test
    fun `bootstrap adapters never pretend to have a vehicle or navigation connection`() {
        assertEquals(ConnectionState.DISCONNECTED, UnconnectedVehicleGateway().connection.value)
        assertEquals(ConnectionState.UNAVAILABLE, UnconnectedNavigationGateway().connection.value)
        assertFalse(UnknownDrivingRestrictionsProvider().restrictions.value.allowsDetailedInteraction)
    }
}
