package io.github.hangillab.hangil.core.domain

import java.util.Collections

/** An explicitly copied practice target, never evidence of a driven or automatically matched road. */
class PlannedPracticeRoad(
    val sourceSessionId: String,
    val reviewVersion: String,
    road: CourseRoadSnapshot,
) {
    val road: CourseRoadSnapshot = road.copy(nodes = Collections.unmodifiableList(ArrayList(road.nodes)))
    val origin: String get() = "USER_REVIEW_SELECTION"

    init {
        require(sourceSessionId.isNotBlank() && reviewVersion.isNotBlank()) { "Invalid planned road source" }
    }

    override fun equals(other: Any?): Boolean =
        other is PlannedPracticeRoad && sourceSessionId == other.sourceSessionId &&
            reviewVersion == other.reviewVersion && road == other.road

    override fun hashCode(): Int = 31 * (31 * sourceSessionId.hashCode() + reviewVersion.hashCode()) + road.hashCode()

    override fun toString(): String = "PlannedPracticeRoad(origin=$origin, snapshot=redacted)"

    companion object {
        fun from(review: SessionReviewRoad): PlannedPracticeRoad = PlannedPracticeRoad(review.source.sessionId, review.version, review.road)
    }
}
