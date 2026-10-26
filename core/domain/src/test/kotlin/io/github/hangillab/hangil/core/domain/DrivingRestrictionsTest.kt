package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DrivingRestrictionsTest {
    @Test
    fun `unknown status never allows detailed interaction`() {
        assertFalse(DrivingRestrictions().allowsDetailedInteraction)
        assertFalse(DrivingRestrictions(ParkingState.PARKED).allowsDetailedInteraction)
        assertFalse(DrivingRestrictions(uxRestrictionState = UxRestrictionState.UNRESTRICTED).allowsDetailedInteraction)
    }

    @Test
    fun `both confirmed parking and unrestricted UX are required`() {
        for (parking in ParkingState.entries) {
            for (ux in UxRestrictionState.entries) {
                assertEquals(
                    "$parking / $ux",
                    parking == ParkingState.PARKED && ux == UxRestrictionState.UNRESTRICTED,
                    DrivingRestrictions(parking, ux).allowsDetailedInteraction,
                )
            }
        }
    }
}
