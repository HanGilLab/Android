package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import kotlinx.coroutines.flow.Flow

/** Existing collapsed-history scenarios must not start a memo query or mutation. */
internal object UnexpectedHistoryMemoRepository : SessionMemoRepository {
    override fun observe(sessionId: String): Flow<SessionMemo?> = error("Unexpected history memo read")

    override suspend fun save(
        sessionId: String,
        text: String,
        expectedVersion: String?,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult = error("History memo writes are outside this fixture")

    override suspend fun delete(
        sessionId: String,
        expectedVersion: String,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult = error("History memo deletion is outside this fixture")
}
