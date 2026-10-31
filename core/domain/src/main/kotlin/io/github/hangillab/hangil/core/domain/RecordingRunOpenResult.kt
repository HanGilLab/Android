package io.github.hangillab.hangil.core.domain

sealed interface RecordingRunOpenResult {
    data class Opened(
        val run: VehicleRecordingRun,
    ) : RecordingRunOpenResult

    data object AlreadyOpen : RecordingRunOpenResult

    data object SourceChanged : RecordingRunOpenResult

    data object Blocked : RecordingRunOpenResult

    data object InvalidTime : RecordingRunOpenResult
}
