package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CourseRoadSnapshotTest {
    private val first = CourseRoadSnapshot.Node(1, 2, 37.0, 127.0)
    private val second = CourseRoadSnapshot.Node(2, 3, 37.1, 127.1)
    private val third = CourseRoadSnapshot.Node(3, 4, 37.2, 127.0)
    private val road = CourseRoadSnapshot("road", "공개 도로 fixture", "residential", 10, 5, 1_000, listOf(second, first, third))

    @Test fun `road preserves node order and independently pinned source versions`() {
        assertEquals(listOf(second, first, third), road.nodes)
        assertEquals(listOf(3, 2, 4), road.nodes.map { it.osmNodeVersion })
        assertEquals("OSM_WAY_NODES", road.sourceCode)
        assertEquals("https://api.openstreetmap.org/api/0.6/way/10/5.json", road.sourceUrl)
        assertEquals(1_000L, road.capturedAtEpochMillis)
    }

    @Test fun `a closed way preserves the repeated identical node`() {
        val closed = road.copy(nodes = listOf(first, second, third, first))
        assertEquals(listOf(first, second, third, first), closed.nodes)
    }

    @Test fun `conflicting copies of a node and zero length ways are rejected`() {
        for (conflict in listOf(first.copy(osmNodeVersion = 9), first.copy(longitude = 127.2))) {
            assertThrows(IllegalArgumentException::class.java) { road.copy(nodes = listOf(first, second, conflict)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            road.copy(nodes = listOf(first, first.copy(osmNodeId = 4)))
        }
    }

    @Test fun `a course cannot give different roads the same selection identity`() {
        val course =
            PracticeCourse(
                "course",
                1,
                1,
                "fixture course",
                "description",
                listOf("section"),
                listOf(PracticeTask("task", "fixture task", "description")),
                setOf("goal"),
                roadSnapshots = listOf(road),
            )
        assertThrows(IllegalArgumentException::class.java) {
            course.copy(roadSnapshots = listOf(road, road.copy(title = "another road")))
        }
    }
}
