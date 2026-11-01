package io.github.hangillab.hangil.core.domain

sealed interface LocationRecordingEndResult {
    data class Ended(
        val run: LocationRecordingRun,
    ) : LocationRecordingEndResult

    data class AlreadyEnded(
        val run: LocationRecordingRun,
    ) : LocationRecordingEndResult

    data object MissingRun : LocationRecordingEndResult

    data object OwnerChanged : LocationRecordingEndResult

    data object InvalidTime : LocationRecordingEndResult

    data object InvalidTermination : LocationRecordingEndResult
}
