package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo

/** One selected historical user note and revocable, explicit edits; never an SDK observation. */
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

    /** Expired source/host does not assert that the stored note was deleted. */
    data class Unavailable(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryMemoUiState

    /** Only a real null receipt means the selected record has no note. */
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
        val retainedDraft: String? = null,
    ) : SessionHistoryMemoUiState

    data class Ready(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val memo: SessionMemo,
        val canEdit: Boolean = false,
        val feedback: Feedback? = null,
    ) : SessionHistoryMemoUiState

    data class Editing(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val memo: SessionMemo,
        val draft: String,
        val nonce: Long,
    ) : SessionHistoryMemoUiState

    data class Saving(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val memo: SessionMemo,
        val draft: String,
        val nonce: Long,
    ) : SessionHistoryMemoUiState

    enum class Feedback { SAVED, RELOADED }

    enum class FailureReason {
        MEMO_READ,
        MEMO_STREAM_COMPLETED,
        INVALID_MEMO,
        CONFLICT,
        BLOCKED,
        INVALID_TEXT,
        SESSION_CHANGED,
        SESSION_ACTIVE,
        MISSING_SESSION,
        WRITE_UNCONFIRMED,
        INVALID_SAVE_RESULT,
    }
}
