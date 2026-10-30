package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget

/** A confirmation belongs to one visible reader and one immutable stored-record snapshot. */
sealed interface SessionDeletionUiState {
    val nonce: Long?

    data object Idle : SessionDeletionUiState {
        override val nonce: Long? = null
    }

    data class Preparing(
        override val nonce: Long,
        val session: PracticeSession,
    ) : SessionDeletionUiState

    data class Confirming(
        override val nonce: Long,
        val target: SessionDeletionTarget,
    ) : SessionDeletionUiState

    data class Deleting(
        override val nonce: Long,
        val target: SessionDeletionTarget,
    ) : SessionDeletionUiState

    data class Failed(
        override val nonce: Long,
        val session: PracticeSession,
        val reason: FailureReason,
    ) : SessionDeletionUiState

    data class Deleted(
        override val nonce: Long,
        val session: PracticeSession,
    ) : SessionDeletionUiState

    enum class FailureReason { MISSING, ACTIVE, CHANGED, BLOCKED, PREPARE_FAILED, DELETE_FAILED }
}
