package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSelection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

internal class RoomNextPracticeRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
        private val catalog: PracticeCatalog,
    ) : NextPracticeRepository {
        override suspend fun saveSelection(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): NextPracticeSaveResult =
            try {
                database.withTransaction {
                    validate(seed, selection, canCommit)
                    database.preparationDao().upsert(
                        PreparationEntity(
                            goalId = selection.goalId,
                            courseId = selection.courseId,
                            courseVersion = selection.courseVersion,
                            briefingVersion = selection.briefingVersion,
                            taskId = selection.taskId,
                        ),
                    )
                    validate(seed, selection, canCommit)
                    NextPracticeSaveResult.Saved
                }
            } catch (rejected: WriteRejected) {
                rejected.result
            }

        private suspend fun validate(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ) {
            currentCoroutineContext().ensureActive()
            val permitted = canCommit()
            currentCoroutineContext().ensureActive()
            if (!permitted) throw WriteRejected(NextPracticeSaveResult.Blocked)
            if (database.practiceSessionDao().active() != null ||
                database.practiceSessionDao().latestFinished()?.session() != seed.source
            ) {
                throw WriteRejected(NextPracticeSaveResult.SourceChanged)
            }
            val fresh = NextPracticeSeed.resolve(catalog, seed.source)
            if (fresh == null || fresh != seed || !matchesTarget(fresh, selection)) {
                throw WriteRejected(NextPracticeSaveResult.Unavailable)
            }
            currentCoroutineContext().ensureActive()
        }

        private fun matchesTarget(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
        ): Boolean {
            val practice = seed.practice
            val task = practice.course.tasks.singleOrNull { it.id == selection.taskId } ?: return false
            return selection.goalId == practice.goal.id && selection.courseId == practice.course.id &&
                selection.courseVersion == practice.course.version && selection.briefingVersion == practice.course.briefingVersion &&
                task.id.isNotBlank() && task.title.isNotBlank()
        }

        private class WriteRejected(
            val result: NextPracticeSaveResult,
        ) : RuntimeException()
    }
