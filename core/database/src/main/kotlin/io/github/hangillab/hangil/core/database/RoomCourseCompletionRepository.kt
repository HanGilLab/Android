package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

internal class RoomCourseCompletionRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : CourseCompletionRepository {
        override fun observe(sessionId: String) = database.courseCompletionDeclarationDao().observe(sessionId).map { it?.declaration() }

        override suspend fun declare(
            expectedSource: PracticeSession,
            declaredAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): CourseCompletionDeclarationResult =
            try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val sessions = database.practiceSessionDao()
                    val entity = sessions.find(expectedSource.sessionId)
                    val actual =
                        entity?.let {
                            check(sessions.countValidDeletionStorage(expectedSource.sessionId) == 1) {
                                "Invalid completion declaration source storage"
                            }
                            it.session()
                        }
                    val result =
                        when {
                            actual == null -> CourseCompletionDeclarationResult.MissingSession
                            actual != expectedSource -> CourseCompletionDeclarationResult.SourceChanged
                            actual.isActive || sessions.unfinishedCount() != 0L -> CourseCompletionDeclarationResult.ActiveSession
                            declaredAtEpochMillis <= 0 -> CourseCompletionDeclarationResult.InvalidTime
                            else -> save(actual, declaredAtEpochMillis)
                        }
                    requirePermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                CourseCompletionDeclarationResult.Blocked
            }

        private suspend fun save(
            source: PracticeSession,
            declaredAtEpochMillis: Long,
        ): CourseCompletionDeclarationResult {
            val dao = database.courseCompletionDeclarationDao()
            val previous = dao.find(source.sessionId)?.declaration()
            if (previous != null) {
                return if (previous.source == source) {
                    CourseCompletionDeclarationResult.AlreadyDeclared(previous)
                } else {
                    CourseCompletionDeclarationResult.SourceChanged
                }
            }
            val declaration =
                CourseCompletionDeclaration(
                    source,
                    UUID.randomUUID().toString(),
                    declaredAtEpochMillis,
                    CourseCompletionOrigin.USER_DECLARATION,
                )
            dao.insert(CourseCompletionDeclarationEntity.from(declaration))
            return CourseCompletionDeclarationResult.Saved(declaration)
        }

        private suspend fun requirePermission(canCommit: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            val allowed = canCommit()
            currentCoroutineContext().ensureActive()
            if (!allowed) throw WriteBlocked()
        }

        private class WriteBlocked : RuntimeException()
    }
