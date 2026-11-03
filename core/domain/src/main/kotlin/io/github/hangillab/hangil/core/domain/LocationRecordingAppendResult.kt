package io.github.hangillab.hangil.core.domain

sealed interface LocationRecordingAppendResult {
    data class Appended(
        val event: LocationRecordingEvent,
        val reachedLimit: Boolean,
    ) : LocationRecordingAppendResult

    data object MissingSession : LocationRecordingAppendResult

    data object FinishedSession : LocationRecordingAppendResult

    data object SourceChanged : LocationRecordingAppendResult

    data object MissingRun : LocationRecordingAppendResult

    data object OwnerChanged : LocationRecordingAppendResult

    data object RunClosed : LocationRecordingAppendResult

    data object Blocked : LocationRecordingAppendResult

    data object InvalidTime : LocationRecordingAppendResult

    data object LimitReached : LocationRecordingAppendResult
}
