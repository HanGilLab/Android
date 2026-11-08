package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class RoomPracticeRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : PracticeRepository {
        override fun observeSelection() = database.preparationDao().observeChecked().map { it?.selection() }

        override suspend fun saveSelection(
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): Boolean = replace(null, selection, canCommit, compareCurrent = false)

        override suspend fun replaceSelection(
            expectedCurrent: PracticeSelection?,
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): Boolean = replace(expectedCurrent, selection, canCommit, compareCurrent = true)

        private suspend fun replace(
            expectedCurrent: PracticeSelection?,
            selection: PracticeSelection,
            canCommit: () -> Boolean,
            compareCurrent: Boolean,
        ): Boolean =
            try {
                database.withTransaction {
                    permission(canCommit)
                    val dao = database.preparationDao()
                    val current = dao.currentChecked()?.selection()
                    if (compareCurrent && current != expectedCurrent) throw WriteBlocked()
                    val sameContext =
                        current != null && current.goalId == selection.goalId && current.courseId == selection.courseId &&
                            current.courseVersion == selection.courseVersion && current.briefingVersion == selection.briefingVersion
                    val target = if (sameContext) current?.plannedRoad else null
                    if (selection.plannedRoad != target) throw WriteBlocked()
                    // Legacy callers cannot erase a copied target without the exact observed preparation.
                    if (!compareCurrent && current?.plannedRoad != null && current != selection) throw WriteBlocked()
                    dao.upsert(
                        PreparationEntity(
                            goalId = selection.goalId,
                            courseId = selection.courseId,
                            courseVersion = selection.courseVersion,
                            briefingVersion = selection.briefingVersion,
                            taskId = selection.taskId,
                            plannedRoadJson = PlannedPracticeRoadJson.encode(selection.plannedRoad),
                        ),
                    )
                    permission(canCommit)
                    if (dao.currentChecked()?.selection() != selection) throw WriteBlocked()
                }
                true
            } catch (_: WriteBlocked) {
                false
            }

        private suspend fun permission(canCommit: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            if (!canCommit()) throw WriteBlocked()
            currentCoroutineContext().ensureActive()
        }

        private class WriteBlocked : RuntimeException()
    }
