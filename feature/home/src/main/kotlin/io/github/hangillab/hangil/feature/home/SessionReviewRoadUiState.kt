package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionReviewRoad

sealed interface SessionReviewRoadUiState {
    val source: PracticeSession
    val token: Token

    data class Token(
        val readerId: Long,
        val queryNonce: Long,
        val revision: Long,
    )

    data class Loading(
        override val source: PracticeSession,
        override val token: Token,
    ) : SessionReviewRoadUiState

    data class Locked(
        override val source: PracticeSession,
        override val token: Token,
    ) : SessionReviewRoadUiState

    data class Unavailable(
        override val source: PracticeSession,
        override val token: Token,
    ) : SessionReviewRoadUiState

    data class Failed(
        override val source: PracticeSession,
        override val token: Token,
        val writeUnconfirmed: Boolean,
        val retainedMemo: String? = null,
    ) : SessionReviewRoadUiState

    data class Ready(
        override val source: PracticeSession,
        override val token: Token,
        val stored: SessionReviewRoad?,
        val candidates: List<CourseRoadSnapshot>,
        val draft: CourseRoadSnapshot?,
        val busy: Boolean,
        val canWrite: Boolean,
        val feedback: Feedback? = null,
        val edit: Edit? = null,
        val confirmation: Confirmation? = null,
        val retainedMemo: Edit? = null,
    ) : SessionReviewRoadUiState {
        val draftRoadId: String? get() = draft?.id
        val canChangeSample: Boolean
            get() = canWrite && !busy && confirmation == null && edit == null && retainedMemo == null
    }

    data class Edit(
        val nonce: Long,
        val text: String,
        val expectedCurrent: SessionReviewRoad,
    )

    data class Confirmation(
        val nonce: Long,
        val target: CourseRoadSnapshot?,
        val expectedCurrent: SessionReviewRoad,
    )

    enum class Feedback { SAVED, CLEARED, MEMO_SAVED, MEMO_CLEARED, SAMPLE_SAVED, SAMPLE_CLEARED }
}
