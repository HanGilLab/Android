package io.github.hangillab.hangil.core.domain

/** Terminal outcomes of one explicitly requested recording run, not SDK event counts. */
sealed interface VehicleObservationRecordingResult {
    /** Number of append calls acknowledged with Appended during this run. */
    val appendedCount: Long

    data object AlreadyRunning : VehicleObservationRecordingResult {
        override val appendedCount: Long = 0
    }

    data class MissingSession(
        override val appendedCount: Long,
    ) : VehicleObservationRecordingResult

    data class FinishedSession(
        override val appendedCount: Long,
    ) : VehicleObservationRecordingResult

    data class InvalidTime(
        override val appendedCount: Long,
    ) : VehicleObservationRecordingResult

    data class WriteFailed(
        override val appendedCount: Long,
        val cause: Exception,
    ) : VehicleObservationRecordingResult
}
