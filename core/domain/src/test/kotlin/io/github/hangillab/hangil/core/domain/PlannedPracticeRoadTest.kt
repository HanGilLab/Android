package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlannedPracticeRoadTest {
    @Test fun `target freezes the complete ordered shape and exposes no source or geometry through toString`() {
        val nodes = mutableListOf(CourseRoadSnapshot.Node(1, 2, -0.0, 127.0), CourseRoadSnapshot.Node(3, 4, 0.1, 127.1))
        val road = CourseRoadSnapshot("road", "Road", "residential", 9, 2, 100, nodes)
        val target = PlannedPracticeRoad("private-source", "private-version", road)
        nodes.clear()
        assertEquals(2, target.road.nodes.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (target.road.nodes as MutableList<CourseRoadSnapshot.Node>).clear()
        }
        assertEquals("USER_REVIEW_SELECTION", target.origin)
        assertEquals(target, PlannedPracticeRoad("private-source", "private-version", target.road))
        assertNotEquals(target, PlannedPracticeRoad("private-source", "other-version", target.road))
        assertFalse(target.toString().contains("private"))
        assertFalse(target.toString().contains("127"))
    }
}
