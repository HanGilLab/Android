package io.github.hangillab.hangil.core.domain

/** A frozen OSM way bounding-box center, not an entrance, road point, route or SDK position. */
data class CoursePreviewPoint(
    val id: String,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val osmWayId: Long,
    val osmVersion: Int,
) {
    init {
        require(id.isNotBlank()) { "Invalid preview point ID" }
        require(title.isNotBlank()) { "Invalid preview point title" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid preview point latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid preview point longitude" }
        require(osmWayId > 0) { "Invalid preview point OSM way ID" }
        require(osmVersion > 0) { "Invalid preview point OSM version" }
    }

    val sourceUrl: String get() = "https://api.openstreetmap.org/api/0.6/way/$osmWayId/$osmVersion.json"
}
