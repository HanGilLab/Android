package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.vehicle.constant.ErrorCode
import io.github.hangillab.hangil.core.domain.VehicleReading
import ai.pleos.playground.vehicle.callback.exception.a as PleosSdkVehicleException

// SDK 2.3.2 배포 ABI는 문서의 VehicleException/errorCode를 a/a()로 노출한다.
// SDK 버전 변경 시 실제 바이너리를 사용하는 계약 테스트를 함께 확인한다.
internal fun Exception.toReadingFailure(): VehicleReading<Nothing> =
    when {
        this is SecurityException || cause is SecurityException -> VehicleReading.PermissionDenied
        this is UnsupportedOperationException -> VehicleReading.Unsupported
        this is PleosSdkVehicleException ->
            when (this.a()) {
                ErrorCode.ERROR_SECURITY_PERMISSION -> VehicleReading.PermissionDenied
                ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY,
                ErrorCode.ERROR_NOT_SUPPORTED_AREA,
                -> VehicleReading.Unsupported
                ErrorCode.ERROR_EMPTY_RESULT -> VehicleReading.Unknown
                else -> VehicleReading.Error
            }
        else -> VehicleReading.Error
    }
