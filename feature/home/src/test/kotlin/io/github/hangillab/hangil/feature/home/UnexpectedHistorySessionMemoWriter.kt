package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult

/** Existing read-only fixtures must fail if a new history write is accidentally reached. */
internal object UnexpectedHistorySessionMemoWriter : HistorySessionMemoWriter {
    override suspend fun update(
        expectedSource: PracticeSession,
        expectedMemo: SessionMemo,
        text: String,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult = throw AssertionError("Unexpected history memo write")
}
