package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import java.util.UUID

/** One joined SQLite read includes even a parent whose node rows are missing. */
internal data class SessionReviewRoadRow(
    @Embedded val entity: SessionReviewRoadEntity,
    @Embedded(prefix = "node_") val node: SessionReviewRoadNodeEntity?,
    val validStorage: Int,
    val nodeValidStorage: Int,
) {
    companion object {
        /** Call within the caller's Room transaction so header, original event and owner are one snapshot. */
        suspend fun readValidated(
            database: PreparationDatabase,
            sessionId: String,
        ): SessionReviewRoad? {
            val stored = review(database.sessionReviewRoadDao().find(sessionId)) ?: return null
            stored.sampleAnchor?.let { anchor ->
                val sessions = database.practiceSessionDao()
                val source = sessions.find(sessionId)
                check(
                    source != null && sessions.countValidDeletionStorage(sessionId) == 1 && source.session() == stored.source &&
                        sampleMatches(database, stored.source, anchor),
                ) { "Stored linked review sample changed" }
            }
            return stored
        }

        suspend fun sampleMatches(
            database: PreparationDatabase,
            source: PracticeSession,
            sample: SessionReviewSample,
        ): Boolean {
            if (sample.event.sessionId != source.sessionId) return false
            val dao = database.locationRecordingDao()
            val event = dao.findEvent(sample.event.id)?.event() ?: return false
            if (event != sample.event) return false
            val run = dao.findRun(event.runId)?.run() ?: return false
            val endedElapsed = run.endedAtElapsedRealtimeMillis
            if (run.source != source.copy(endedAtEpochMillis = null) ||
                (endedElapsed != null && endedElapsed < event.reading.receivedAtElapsedRealtimeMillis)
            ) {
                return false
            }
            return SessionReviewSample.from(run, event).sameCallback(sample)
        }

        fun review(rows: List<SessionReviewRoadRow>): SessionReviewRoad? {
            if (rows.isEmpty()) return null
            val first = rows.first().entity
            check(rows.all { it.validStorage == 1 && it.entity == first }) { "Invalid stored review road header" }
            check(first.origin == "USER_SELECTION") { "Invalid stored review road origin" }
            check(runCatching { UUID.fromString(first.version).toString() == first.version }.getOrDefault(false)) {
                "Invalid stored review road version"
            }
            check(first.nodeCount >= 2 && rows.size == first.nodeCount) { "Incomplete stored review road nodes" }
            val nodes =
                rows.mapIndexed { index, row ->
                    check(row.nodeValidStorage == 1) { "Invalid stored review road node storage" }
                    val node = checkNotNull(row.node) { "Missing stored review road node" }
                    check(node.sessionId == first.sessionId && node.ordinal == index) { "Invalid stored review road node order" }
                    node.node()
                }
            return SessionReviewRoad(
                first.source(),
                CourseRoadSnapshot(
                    first.roadId,
                    first.roadTitle,
                    first.highway,
                    first.osmWayId,
                    first.osmWayVersion,
                    first.capturedAtEpochMillis,
                    nodes,
                ),
                first.version,
                first.memoText,
                SessionReviewSampleJson.decode(first.sampleAnchorJson),
            )
        }
    }
}
