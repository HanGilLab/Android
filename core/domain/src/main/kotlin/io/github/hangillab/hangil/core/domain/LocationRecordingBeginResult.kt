package io.github.hangillab.hangil.core.domain

sealed interface LocationRecordingBeginResult {
    data class Begun(
        val run: LocationRecordingRun,
    ) : LocationRecordingBeginResult

    data object MissingSession : LocationRecordingBeginResult

    data object FinishedSession : LocationRecordingBeginResult

    data object SourceChanged : LocationRecordingBeginResult

    data object AlreadyOpen : LocationRecordingBeginResult

    data object Blocked : LocationRecordingBeginResult

    data object InvalidTime : LocationRecordingBeginResult
}
