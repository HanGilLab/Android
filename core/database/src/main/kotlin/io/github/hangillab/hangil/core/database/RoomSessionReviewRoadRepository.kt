package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

internal class RoomSessionReviewRoadRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
        private val catalog: PracticeCatalog,
    ) : SessionReviewRoadRepository {
        override fun observe(sessionId: String) =
            database.invalidationTracker
                .createFlow(
                    "session_review_road",
                    "session_review_road_node",
                    "location_recording_run",
                    "location_recording_event",
                    "practice_session",
                ).map {
                    database.withTransaction {
                        SessionReviewRoadRow.readValidated(database, sessionId)
                    }
                }.distinctUntilChanged()

        override suspend fun save(
            expectedSource: PracticeSession,
            road: CourseRoadSnapshot,
            expectedCurrent: SessionReviewRoad?,
            memoText: String,
            discardMemo: Boolean,
            discardSample: Boolean,
            initialSample: SessionReviewSample?,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult {
            var pinned: NextPracticeSeed? = null
            return mutate(expectedSource, expectedCurrent, canCommit, afterPermission = {
                if (pinned != null && NextPracticeSeed.resolve(catalog, expectedSource) != pinned) {
                    throw WriteRejected(SessionReviewRoadWriteResult.UnavailableRoad)
                }
            }) { actual, current ->
                if (memoText.isNotEmpty() && !SessionMemoPolicy.isValidText(memoText)) {
                    return@mutate SessionReviewRoadWriteResult.InvalidMemo
                }
                if (initialSample != null) {
                    if (current != null) return@mutate SessionReviewRoadWriteResult.Conflict
                    if (!sampleMatches(actual, initialSample)) return@mutate SessionReviewRoadWriteResult.SampleChanged
                }
                val sampleAnchor = current?.takeIf { it.road == road }?.sampleAnchor ?: initialSample
                val candidate = SessionReviewRoad(actual, road, nextVersion(current?.version), memoText, sampleAnchor)
                if (current != null && current.road == candidate.road) {
                    if (current.memoText == memoText) {
                        return@mutate SessionReviewRoadWriteResult.Saved(current)
                    }
                    val updated = database.sessionReviewRoadDao().updateMemo(actual.sessionId, current.version, candidate.version, memoText)
                    check(updated == 1)
                    return@mutate SessionReviewRoadWriteResult.Saved(candidate)
                }
                if (current != null) {
                    if (current.memoText.isNotEmpty() && !discardMemo) {
                        return@mutate SessionReviewRoadWriteResult.MemoPresent
                    }
                    if (current.sampleAnchor != null && !discardSample) return@mutate SessionReviewRoadWriteResult.SamplePresent
                    if (memoText.isNotEmpty()) return@mutate SessionReviewRoadWriteResult.InvalidMemo
                }
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
                    write(candidate, current)
                    SessionReviewRoadWriteResult.Saved(candidate)
                }
            }
        }

        override suspend fun clear(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            discardMemo: Boolean,
            discardSample: Boolean,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult =
            mutate(expectedSource, expectedCurrent, canCommit) { actual, current ->
                if (current == null) {
                    SessionReviewRoadWriteResult.Conflict
                } else if (current.memoText.isNotEmpty() && !discardMemo) {
                    SessionReviewRoadWriteResult.MemoPresent
                } else if (current.sampleAnchor != null && !discardSample) {
                    SessionReviewRoadWriteResult.SamplePresent
                } else {
                    check(database.sessionReviewRoadDao().delete(actual.sessionId, current.version) == 1)
                    SessionReviewRoadWriteResult.Cleared
                }
            }

        override suspend fun setSample(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            sample: SessionReviewSample?,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult =
            mutate(expectedSource, expectedCurrent, canCommit) { actual, current ->
                if (current == null) {
                    SessionReviewRoadWriteResult.Conflict
                } else if (sample != null && !sampleMatches(actual, sample)) {
                    SessionReviewRoadWriteResult.SampleChanged
                } else if (current.sampleAnchor == sample) {
                    SessionReviewRoadWriteResult.Saved(current)
                } else {
                    val saved = SessionReviewRoad(actual, current.road, nextVersion(current.version), current.memoText, sample)
                    check(
                        database.sessionReviewRoadDao().updateSample(
                            actual.sessionId,
                            current.version,
                            saved.version,
                            SessionReviewSampleJson.encode(sample),
                        ) ==
                            1,
                    )
                    SessionReviewRoadWriteResult.Saved(saved)
                }
            }

        private suspend fun mutate(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad?,
            canCommit: () -> Boolean,
            afterPermission: suspend () -> Unit = {},
            action: suspend (PracticeSession, SessionReviewRoad?) -> SessionReviewRoadWriteResult,
        ): SessionReviewRoadWriteResult =
            try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val sessions = database.practiceSessionDao()
                    val actual = readSource(expectedSource.sessionId)
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
                    if (result is SessionReviewRoadWriteResult.Saved || result == SessionReviewRoadWriteResult.Cleared) {
                        if (readSource(expectedSource.sessionId) !=
                            expectedSource
                        ) {
                            throw WriteRejected(SessionReviewRoadWriteResult.SourceChanged)
                        }
                        if (sessions.unfinishedCount() != 0L) throw WriteRejected(SessionReviewRoadWriteResult.SessionActive)
                        val stored = SessionReviewRoadRow.review(database.sessionReviewRoadDao().find(expectedSource.sessionId))
                        when (result) {
                            is SessionReviewRoadWriteResult.Saved -> {
                                if (stored != result.review) throw WriteRejected(SessionReviewRoadWriteResult.Conflict)
                                checkNotNull(stored).sampleAnchor?.let { anchor ->
                                    if (!sampleMatches(
                                            expectedSource,
                                            anchor,
                                        )
                                    ) {
                                        throw WriteRejected(SessionReviewRoadWriteResult.SampleChanged)
                                    }
                                }
                            }
                            else -> if (stored != null) throw WriteRejected(SessionReviewRoadWriteResult.Conflict)
                        }
                    }
                    // No database suspension follows this final pure live owner/parking/UX check.
                    requirePermission(canCommit)
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
                        entity.memoText,
                        entity.sampleAnchorJson,
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

        private suspend fun sampleMatches(
            source: PracticeSession,
            sample: SessionReviewSample,
        ): Boolean = SessionReviewRoadRow.sampleMatches(database, source, sample)

        private suspend fun readSource(sessionId: String): PracticeSession? {
            val sessions = database.practiceSessionDao()
            val entity = sessions.find(sessionId) ?: return null
            check(sessions.countValidDeletionStorage(sessionId) == 1) { "Invalid review road source storage" }
            return entity.session().also { source ->
                val selection = source.selection
                check(
                    source.sessionId.isNotBlank() && selection.goalId.isNotBlank() && selection.courseId.isNotBlank() &&
                        selection.taskId.isNotBlank() && selection.courseVersion > 0 && selection.briefingVersion > 0 &&
                        source.goalTitle.isNotBlank() && source.courseTitle.isNotBlank() && source.taskTitle.isNotBlank(),
                ) { "Invalid review road source snapshot" }
            }
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
