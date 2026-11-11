package io.github.hangillab.hangil.core.domain

/** Inclusive positions in the exact, versioned public way used for this review. */
data class CourseRoadSection(
    val firstNodeIndex: Int,
    val lastNodeIndex: Int,
) {
    init {
        require(firstNodeIndex >= 0 && lastNodeIndex > firstNodeIndex) { "Invalid public road section order" }
    }

    fun isValidFor(road: CourseRoadSnapshot): Boolean {
        if (lastNodeIndex >= road.nodes.size) return false
        val first = road.nodes[firstNodeIndex]
        return road.nodes.subList(firstNodeIndex + 1, lastNodeIndex + 1).any {
            it.latitude != first.latitude || it.longitude != first.longitude
        }
    }

    fun nodesOf(road: CourseRoadSnapshot): List<CourseRoadSnapshot.Node> {
        require(isValidFor(road)) { "Invalid section for the public road snapshot" }
        return road.nodes.subList(firstNodeIndex, lastNodeIndex + 1)
    }
}
