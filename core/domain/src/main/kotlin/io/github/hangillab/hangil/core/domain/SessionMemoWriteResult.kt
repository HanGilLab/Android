package io.github.hangillab.hangil.core.domain

sealed interface SessionMemoWriteResult {
    data class Saved(
        val memo: SessionMemo,
    ) : SessionMemoWriteResult

    data object Deleted : SessionMemoWriteResult

    data object Blocked : SessionMemoWriteResult

    data object SessionChanged : SessionMemoWriteResult

    data object MissingSession : SessionMemoWriteResult

    data object SessionActive : SessionMemoWriteResult

    data object Conflict : SessionMemoWriteResult

    data object InvalidText : SessionMemoWriteResult
}
