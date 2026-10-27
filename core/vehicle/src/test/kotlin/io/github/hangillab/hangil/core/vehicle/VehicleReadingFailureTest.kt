package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.vehicle.constant.ErrorCode
import io.github.hangillab.hangil.core.domain.VehicleReading
import org.junit.Assert.assertEquals
import org.junit.Test
import ai.pleos.playground.vehicle.callback.exception.a as PleosSdkVehicleException

class VehicleReadingFailureTest {
    @Test
    fun `SDK permission error is permission denied`() {
        assertEquals(
            VehicleReading.PermissionDenied,
            sdkFailure(ErrorCode.ERROR_SECURITY_PERMISSION).toReadingFailure(),
        )
    }

    @Test
    fun `SDK unsupported property and area are unsupported readings`() {
        listOf(ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY, ErrorCode.ERROR_NOT_SUPPORTED_AREA).forEach { code ->
            assertEquals(code.name, VehicleReading.Unsupported, sdkFailure(code).toReadingFailure())
        }
    }

    @Test
    fun `SDK empty result is unknown rather than a value or error`() {
        assertEquals(VehicleReading.Unknown, sdkFailure(ErrorCode.ERROR_EMPTY_RESULT).toReadingFailure())
    }

    @Test
    fun `SDK type service and other failures remain errors`() {
        listOf(
            ErrorCode.ERROR_NOT_SUPPORTED_VALUE_TYPE,
            ErrorCode.ERROR_SERVICE_CONNECTION_FAILURE,
            ErrorCode.ERROR_INTERNAL_SERVICE,
            ErrorCode.ERROR_DRIVING_RESTRICTED,
            ErrorCode.ERROR_UNDEFINED,
        ).forEach { code ->
            assertEquals(code.name, VehicleReading.Error, sdkFailure(code).toReadingFailure())
        }
    }

    @Test
    fun `standard permission and unsupported exceptions retain their classifications`() {
        assertEquals(VehicleReading.PermissionDenied, SecurityException().toReadingFailure())
        assertEquals(VehicleReading.PermissionDenied, Exception(SecurityException()).toReadingFailure())
        assertEquals(VehicleReading.Unsupported, UnsupportedOperationException().toReadingFailure())
        assertEquals(
            VehicleReading.PermissionDenied,
            UnsupportedOperationException(SecurityException()).toReadingFailure(),
        )
    }

    @Test
    fun `unclassified exceptions and unrecognized SDK errors remain errors`() {
        assertEquals(VehicleReading.Error, IllegalStateException().toReadingFailure())
        assertEquals(VehicleReading.Error, PleosSdkVehicleException("unrecognized error").toReadingFailure())
    }

    private fun sdkFailure(code: ErrorCode) = PleosSdkVehicleException(code.id.toString())
}
