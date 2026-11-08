package io.github.hangillab.hangil.core.domain

sealed interface SessionReviewRoadWriteResult {
    data class Saved(
        val review: SessionReviewRoad,
    ) : SessionReviewRoadWriteResult

    data object Cleared : SessionReviewRoadWriteResult

    data object MissingSession : SessionReviewRoadWriteResult

    data object SourceChanged : SessionReviewRoadWriteResult

    data object SessionActive : SessionReviewRoadWriteResult

    data object Conflict : SessionReviewRoadWriteResult

    data object UnavailableRoad : SessionReviewRoadWriteResult

    data object InvalidMemo : SessionReviewRoadWriteResult

    data object MemoPresent : SessionReviewRoadWriteResult

    data object SampleChanged : SessionReviewRoadWriteResult

    data object SamplePresent : SessionReviewRoadWriteResult

    data object Blocked : SessionReviewRoadWriteResult
}
