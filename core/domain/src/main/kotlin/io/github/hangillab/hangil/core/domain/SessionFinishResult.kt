package io.github.hangillab.hangil.core.domain

sealed interface SessionFinishResult {
    data class Finished(
        val session: PracticeSession,
    ) : SessionFinishResult

    data class AlreadyFinished(
        val session: PracticeSession,
    ) : SessionFinishResult

    data object SourceChanged : SessionFinishResult

    data object Missing : SessionFinishResult

    data object Blocked : SessionFinishResult

    data object InvalidTime : SessionFinishResult
}
