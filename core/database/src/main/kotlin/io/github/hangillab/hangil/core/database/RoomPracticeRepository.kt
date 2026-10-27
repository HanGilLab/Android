package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class RoomPracticeRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : PracticeRepository {
        override fun observeSelection() = database.preparationDao().observe().map { it?.selection() }

        override suspend fun saveSelection(
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): Boolean =
            try {
                database.withTransaction {
                    if (!canCommit()) throw WriteBlocked()
                    database.preparationDao().upsert(
                        PreparationEntity(
                            goalId = selection.goalId,
                            courseId = selection.courseId,
                            courseVersion = selection.courseVersion,
                            briefingVersion = selection.briefingVersion,
                            taskId = selection.taskId,
                        ),
                    )
                    if (!canCommit()) throw WriteBlocked()
                }
                true
            } catch (_: WriteBlocked) {
                false
            }

        private class WriteBlocked : RuntimeException()
    }
