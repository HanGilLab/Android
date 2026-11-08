package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionReviewRoadTest {
    @Test fun `a review receipt owns immutable road nodes and preserves full value equality`() {
        val source =
            PracticeSession(
                "fixture",
                PracticeSelection("goal", "course", 1, 1, "task"),
                "Goal",
                "Course",
                "Task",
                CourseReviewStatus.DEMO,
                1_000,
                2_000,
            )
        val nodes = mutableListOf(CourseRoadSnapshot.Node(1, 1, 37.0, 127.0), CourseRoadSnapshot.Node(2, 2, 37.1, 127.1))
        val road = CourseRoadSnapshot("road", "fixture road", "residential", 10, 3, 1_000, nodes)
        val review = SessionReviewRoad(source, road, "version")
        val original = SessionReviewRoad(source, road.copy(nodes = nodes.toList()), "version")
        nodes.reverse()
        nodes[0] = nodes[0].copy(osmNodeVersion = 9)
        assertEquals(original, review)
        assertEquals("USER_SELECTION", review.origin)
        assertThrows(UnsupportedOperationException::class.java) { (review.road.nodes as MutableList<*>).clear() }
        assertThrows(IllegalArgumentException::class.java) { SessionReviewRoad(source.copy(endedAtEpochMillis = null), road, "version") }
    }
}
