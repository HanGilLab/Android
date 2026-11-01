package io.github.hangillab.hangil

import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult

/** Older native scenarios never edit an existing historical memo. */
internal object UnexpectedHistorySessionMemoWriter : HistorySessionMemoWriter {
    override suspend fun update(
        expectedSource: PracticeSession,
        expectedMemo: SessionMemo,
        text: String,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult = throw AssertionError("Unexpected historical memo update in a read-only native fixture")
}
