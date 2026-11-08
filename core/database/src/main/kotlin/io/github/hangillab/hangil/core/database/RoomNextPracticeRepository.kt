package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
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
            expectedReview: SessionReviewRoad?,
            canCommit: () -> Boolean,
        ): NextPracticeSaveResult = save(seed, selection, expectedReview, canCommit, latestOnly = true)

        override suspend fun saveHistorySelection(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            expectedReview: SessionReviewRoad?,
            canCommit: () -> Boolean,
        ): NextPracticeSaveResult = save(seed, selection, expectedReview, canCommit, latestOnly = false)

        private suspend fun save(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            expectedReview: SessionReviewRoad?,
            canCommit: () -> Boolean,
            latestOnly: Boolean,
        ): NextPracticeSaveResult =
            try {
                database.withTransaction {
                    validate(seed, selection, expectedReview, canCommit, latestOnly)
                    database.preparationDao().upsert(
                        PreparationEntity(
                            goalId = selection.goalId,
                            courseId = selection.courseId,
                            courseVersion = selection.courseVersion,
                            briefingVersion = selection.briefingVersion,
                            taskId = selection.taskId,
                            plannedRoadJson = PlannedPracticeRoadJson.encode(selection.plannedRoad),
                        ),
                    )
                    validate(seed, selection, expectedReview, canCommit, latestOnly)
                    if (database.preparationDao().currentChecked()?.selection() != selection) {
                        throw WriteRejected(NextPracticeSaveResult.SourceChanged)
                    }
                    NextPracticeSaveResult.Saved
                }
            } catch (rejected: WriteRejected) {
                rejected.result
            }

        private suspend fun validate(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            expectedReview: SessionReviewRoad?,
            canCommit: () -> Boolean,
            latestOnly: Boolean,
        ) {
            currentCoroutineContext().ensureActive()
            val permitted = canCommit()
            currentCoroutineContext().ensureActive()
            if (!permitted) throw WriteRejected(NextPracticeSaveResult.Blocked)
            val sessions = database.practiceSessionDao()
            if (sessions.unfinishedCount() != 0L) throw WriteRejected(NextPracticeSaveResult.SourceChanged)
            val entity = if (latestOnly) sessions.latestFinished() else sessions.find(seed.source.sessionId)
            val source =
                entity?.let {
                    // Room getters can coerce malformed values; bind the raw check to this exact row.
                    check(sessions.countValidNextPracticeSourceStorage(it.rowId, it.sessionId) == 1) {
                        "Invalid next practice source storage"
                    }
                    it.session()
                }
            if (source != seed.source) throw WriteRejected(NextPracticeSaveResult.SourceChanged)
            if (selection.plannedRoad != null) {
                if (expectedReview == null || expectedReview.source != seed.source ||
                    selection.plannedRoad != PlannedPracticeRoad.from(expectedReview) ||
                    SessionReviewRoadRow.readValidated(database, seed.source.sessionId) != expectedReview
                ) {
                    throw WriteRejected(NextPracticeSaveResult.SourceChanged)
                }
            } else if (expectedReview != null) {
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
