package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.flow.Flow

/** Other history fixtures never open or write the independent completion declaration. */
internal object UnexpectedHistoryCourseCompletionRepository : CourseCompletionRepository {
    override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> = error("Unexpected course-completion read")

    override suspend fun declare(
        expectedSource: PracticeSession,
        declaredAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): CourseCompletionDeclarationResult = error("Unexpected course-completion declaration")
}
