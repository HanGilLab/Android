package io.github.hangillab.hangil.core.domain

sealed interface SessionDeletionResult {
    data object Deleted : SessionDeletionResult

    data object Missing : SessionDeletionResult

    data object Active : SessionDeletionResult

    data object Changed : SessionDeletionResult

    data object Blocked : SessionDeletionResult
}
