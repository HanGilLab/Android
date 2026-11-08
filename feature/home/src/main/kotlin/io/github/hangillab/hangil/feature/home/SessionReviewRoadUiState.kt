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
    ) : SessionReviewRoadUiState {
        val draftRoadId: String? get() = draft?.id
    }

    enum class Feedback { SAVED, CLEARED }
}
