package io.github.hangillab.hangil.core.domain

/**
 * An SDK-derived state snapshot at the application's observation time, not the SDK event time.
 * VehicleGateway exposes conflated state, so snapshots cannot represent every SDK callback or
 * physical action. Individual values may have been received before this snapshot was observed.
 * Location and steering are not collected or represented by this model.
 * Nonpositive observation times are rejected by VehicleObservationRepository.append; positive
 * wall-clock regressions remain valid observations and must not be reordered by this timestamp.
 */
data class VehicleObservation(
    val observedAtEpochMillis: Long,
    val state: VehicleState,
) {
    val source: VehicleObservationSource get() = VehicleObservationSource.SDK_STATE_OBSERVATION
}
