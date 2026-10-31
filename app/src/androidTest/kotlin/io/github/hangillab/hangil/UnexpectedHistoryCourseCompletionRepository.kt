package io.github.hangillab.hangil

import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.flow.Flow

/** Existing native history fixtures never expand or mutate the independent completion declaration. */
internal object UnexpectedHistoryCourseCompletionRepository : CourseCompletionRepository {
    override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> = error("Unexpected history completion read")

    override suspend fun declare(
        expectedSource: PracticeSession,
        declaredAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): CourseCompletionDeclarationResult = error("History fixture must not declare course completion")
}
