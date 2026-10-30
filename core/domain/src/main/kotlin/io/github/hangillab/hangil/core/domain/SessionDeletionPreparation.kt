package io.github.hangillab.hangil.core.domain

sealed interface SessionDeletionPreparation {
    data class Ready(
        val target: SessionDeletionTarget,
    ) : SessionDeletionPreparation

    data object Missing : SessionDeletionPreparation

    data object Active : SessionDeletionPreparation

    data object Changed : SessionDeletionPreparation

    data object Blocked : SessionDeletionPreparation
}
