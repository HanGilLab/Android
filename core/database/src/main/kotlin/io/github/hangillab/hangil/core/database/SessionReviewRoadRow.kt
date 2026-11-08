package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import java.util.UUID

/** One joined SQLite read includes even a parent whose node rows are missing. */
internal data class SessionReviewRoadRow(
    @Embedded val entity: SessionReviewRoadEntity,
    @Embedded(prefix = "node_") val node: SessionReviewRoadNodeEntity?,
    val validStorage: Int,
    val nodeValidStorage: Int,
) {
    companion object {
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
            )
        }
    }
}
