package io.github.hangillab.hangil.core.domain

import java.util.Collections

/** A user's review choice, not evidence that this road was driven or matched by a location sample. */
class SessionReviewRoad(
    val source: PracticeSession,
    road: CourseRoadSnapshot,
    val version: String,
    val memoText: String = "",
    val sampleAnchor: SessionReviewSample? = null,
) {
    val road: CourseRoadSnapshot = road.copy(nodes = Collections.unmodifiableList(ArrayList(road.nodes)))
    val origin: String get() = "USER_SELECTION"

    init {
        val selection = source.selection
        require(
            source.sessionId.isNotBlank() && selection.goalId.isNotBlank() && selection.courseId.isNotBlank() &&
                selection.taskId.isNotBlank() && selection.courseVersion > 0 && selection.briefingVersion > 0 &&
                source.goalTitle.isNotBlank() && source.courseTitle.isNotBlank() && source.taskTitle.isNotBlank() &&
                source.startedAtEpochMillis > 0 && source.endedAtEpochMillis != null &&
                source.endedAtEpochMillis >= source.startedAtEpochMillis,
        ) { "Invalid review road source" }
        require(version.isNotBlank()) { "Invalid review road version" }
        require(memoText.isEmpty() || SessionMemoPolicy.isValidText(memoText)) { "Invalid review road memo" }
        require(sampleAnchor == null || sampleAnchor.event.sessionId == source.sessionId) { "Foreign linked review sample" }
    }

    override fun equals(other: Any?): Boolean =
        other is SessionReviewRoad && source == other.source && road == other.road &&
            version == other.version && memoText == other.memoText && sampleAnchor == other.sampleAnchor

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * source.hashCode() + road.hashCode()) + version.hashCode()) + memoText.hashCode()) +
            (sampleAnchor?.hashCode() ?: 0)

    override fun toString(): String = "SessionReviewRoad(origin=$origin, snapshot=redacted)"
}
