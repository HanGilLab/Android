package io.github.hangillab.hangil.core.domain

sealed interface SessionStartResult {
    data class Started(
        val session: PracticeSession,
    ) : SessionStartResult

    data class AlreadyActive(
        val session: PracticeSession,
    ) : SessionStartResult

    data object PreparationChanged : SessionStartResult

    data object Blocked : SessionStartResult

    data object InvalidTime : SessionStartResult
}
