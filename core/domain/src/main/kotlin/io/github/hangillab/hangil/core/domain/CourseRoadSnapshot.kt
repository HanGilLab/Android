package io.github.hangillab.hangil.core.domain

/** A public OSM way and independently pinned node versions, not a reviewed or observed driving route. */
data class CourseRoadSnapshot(
    val id: String,
    val title: String,
    val highway: String,
    val osmWayId: Long,
    val osmWayVersion: Int,
    val capturedAtEpochMillis: Long,
    val nodes: List<Node>,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && highway.isNotBlank())
        require(osmWayId > 0 && osmWayVersion > 0 && capturedAtEpochMillis > 0)
        require(nodes.size >= 2 && nodes.map { it.latitude to it.longitude }.distinct().size >= 2)
        require(nodes.groupBy { it.osmNodeId }.values.all { it.distinct().size == 1 })
    }

    val sourceCode: String get() = "OSM_WAY_NODES"
    val sourceUrl: String get() = "https://api.openstreetmap.org/api/0.6/way/$osmWayId/$osmWayVersion.json"

    data class Node(
        val osmNodeId: Long,
        val osmNodeVersion: Int,
        val latitude: Double,
        val longitude: Double,
    ) {
        init {
            require(osmNodeId > 0 && osmNodeVersion > 0)
            require(latitude.isFinite() && latitude in -90.0..90.0)
            require(longitude.isFinite() && longitude in -180.0..180.0)
        }
    }
}
