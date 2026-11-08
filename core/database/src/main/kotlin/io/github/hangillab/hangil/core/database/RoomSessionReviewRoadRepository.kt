package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

internal class RoomSessionReviewRoadRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
        private val catalog: PracticeCatalog,
    ) : SessionReviewRoadRepository {
        override fun observe(sessionId: String) = database.sessionReviewRoadDao().observe(sessionId).map { SessionReviewRoadRow.review(it) }

        override suspend fun save(
            expectedSource: PracticeSession,
            road: CourseRoadSnapshot,
            expectedCurrent: SessionReviewRoad?,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult {
            var pinned: NextPracticeSeed? = null
            return mutate(expectedSource, expectedCurrent, canCommit, afterPermission = {
                if (pinned != null && NextPracticeSeed.resolve(catalog, expectedSource) != pinned) {
                    throw WriteRejected(SessionReviewRoadWriteResult.UnavailableRoad)
                }
            }) { actual, current ->
                val candidate = SessionReviewRoad(actual, road, nextVersion(current?.version))
                val seed = NextPracticeSeed.resolve(catalog, actual)
                val catalogRoad =
                    seed
                        ?.practice
                        ?.course
                        ?.roadSnapshots
                        ?.singleOrNull { it.id == candidate.road.id }
                if (seed == null || catalogRoad != candidate.road) {
                    SessionReviewRoadWriteResult.UnavailableRoad
                } else {
                    pinned = seed
                    val saved = current?.takeIf { it.road == candidate.road } ?: candidate
                    if (saved !== current) write(saved, current)
                    SessionReviewRoadWriteResult.Saved(saved)
                }
            }
        }

        override suspend fun clear(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult =
            mutate(expectedSource, expectedCurrent, canCommit) { actual, current ->
                if (current == null) {
                    SessionReviewRoadWriteResult.Conflict
                } else {
                    check(database.sessionReviewRoadDao().delete(actual.sessionId, current.version) == 1)
                    SessionReviewRoadWriteResult.Cleared
                }
            }

        private suspend fun mutate(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad?,
            canCommit: () -> Boolean,
            afterPermission: () -> Unit = {},
            action: suspend (PracticeSession, SessionReviewRoad?) -> SessionReviewRoadWriteResult,
        ): SessionReviewRoadWriteResult =
            try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val sessions = database.practiceSessionDao()
                    val entity = sessions.find(expectedSource.sessionId)
                    val actual =
                        entity?.let {
                            val validStorage = sessions.countValidDeletionStorage(expectedSource.sessionId) == 1
                            check(validStorage) { "Invalid review road source storage" }
                            it.session().also { source ->
                                val selection = source.selection
                                val validSource =
                                    source.sessionId.isNotBlank() &&
                                        selection.goalId.isNotBlank() &&
                                        selection.courseId.isNotBlank() &&
                                        selection.taskId.isNotBlank() &&
                                        selection.courseVersion > 0 &&
                                        selection.briefingVersion > 0 &&
                                        source.goalTitle.isNotBlank() &&
                                        source.courseTitle.isNotBlank() &&
                                        source.taskTitle.isNotBlank()
                                check(validSource) { "Invalid review road source snapshot" }
                            }
                        }
                    val result =
                        when {
                            actual == null -> SessionReviewRoadWriteResult.MissingSession
                            actual != expectedSource || (expectedCurrent != null && expectedCurrent.source != actual) ->
                                SessionReviewRoadWriteResult.SourceChanged
                            actual.isActive || sessions.unfinishedCount() != 0L -> SessionReviewRoadWriteResult.SessionActive
                            else -> {
                                val current = SessionReviewRoadRow.review(database.sessionReviewRoadDao().find(actual.sessionId))
                                when {
                                    current != expectedCurrent -> SessionReviewRoadWriteResult.Conflict
                                    current != null && current.source != actual -> SessionReviewRoadWriteResult.SourceChanged
                                    else -> action(actual, current)
                                }
                            }
                        }
                    requirePermission(canCommit)
                    afterPermission()
                    currentCoroutineContext().ensureActive()
                    result
                }
            } catch (rejected: WriteRejected) {
                rejected.result
            }

        private suspend fun write(
            review: SessionReviewRoad,
            current: SessionReviewRoad?,
        ) {
            val dao = database.sessionReviewRoadDao()
            val entity = SessionReviewRoadEntity.from(review)
            if (current == null) {
                dao.insert(entity)
            } else {
                val updated =
                    dao.update(
                        entity.sessionId,
                        current.version,
                        entity.version,
                        entity.roadId,
                        entity.roadTitle,
                        entity.highway,
                        entity.osmWayId,
                        entity.osmWayVersion,
                        entity.capturedAtEpochMillis,
                        entity.nodeCount,
                    )
                check(updated == 1)
                check(dao.deleteNodes(entity.sessionId) == current.road.nodes.size)
            }
            dao.insertNodes(
                review.road.nodes.mapIndexed { ordinal, node ->
                    SessionReviewRoadNodeEntity(
                        entity.sessionId,
                        ordinal,
                        node.osmNodeId,
                        node.osmNodeVersion,
                        node.latitude,
                        node.longitude,
                    )
                },
            )
        }

        private suspend fun requirePermission(canCommit: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            val allowed = canCommit()
            currentCoroutineContext().ensureActive()
            if (!allowed) throw WriteRejected(SessionReviewRoadWriteResult.Blocked)
        }

        private fun nextVersion(previous: String?): String {
            var next: String
            do {
                next = UUID.randomUUID().toString()
            } while (next == previous)
            return next
        }

        private class WriteRejected(
            val result: SessionReviewRoadWriteResult,
        ) : RuntimeException()
    }
