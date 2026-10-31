package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.PracticeSession

/** A user's explicit declaration for one ended snapshot, never a driving-ability certificate. */
sealed interface SessionHistoryCourseCompletionUiState {
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
    ) : SessionHistoryCourseCompletionUiState

    data class Locked(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryCourseCompletionUiState

    data class Unavailable(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryCourseCompletionUiState

    data class Absent(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val canDeclare: Boolean,
    ) : SessionHistoryCourseCompletionUiState

    data class Confirming(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val nonce: Long,
    ) : SessionHistoryCourseCompletionUiState

    data class Saving(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val nonce: Long,
    ) : SessionHistoryCourseCompletionUiState

    data class Ready(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val declaration: CourseCompletionDeclaration,
        val feedback: Feedback? = null,
    ) : SessionHistoryCourseCompletionUiState

    data class Failed(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val reason: FailureReason,
    ) : SessionHistoryCourseCompletionUiState

    enum class Feedback { SAVED, ALREADY_DECLARED }

    enum class FailureReason {
        DECLARATION_READ,
        DECLARATION_STREAM_COMPLETED,
        INVALID_DECLARATION,
        DECLARATION_CHANGED,
        WRITE_UNCONFIRMED,
        INVALID_SAVE_RESULT,
        MISSING_SESSION,
        SOURCE_CHANGED,
        ACTIVE_SESSION,
        BLOCKED,
        INVALID_TIME,
    }
}
