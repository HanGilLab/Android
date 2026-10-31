package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo

/** A read-only user note bound to one immutable history selection, never an SDK observation. */
sealed interface SessionHistoryMemoUiState {
    val source: PracticeSession
    val historyGeneration: Long
    val token: Token

    data class Token(
        val readerId: Long,
        val queryNonce: Long,
        val revision: Long,
    )

    data class Loading(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryMemoUiState

    data class Locked(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryMemoUiState

    /** The captured source/host expired; this does not assert that the note was deleted. */
    data class Unavailable(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryMemoUiState

    /** Only an actual null repository receipt means the selected record has no note. */
    data class Empty(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryMemoUiState

    data class Failed(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val reason: FailureReason,
    ) : SessionHistoryMemoUiState

    data class Ready(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val memo: SessionMemo,
    ) : SessionHistoryMemoUiState

    enum class FailureReason { MEMO_READ, MEMO_STREAM_COMPLETED, INVALID_MEMO }
}
