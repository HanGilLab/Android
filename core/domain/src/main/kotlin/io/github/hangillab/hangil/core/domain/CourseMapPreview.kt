package io.github.hangillab.hangil.core.domain

/** Current catalog illustration only; the points are not a session's observed or navigable route. */
data class CourseMapPreview(
    val id: String,
    val version: Int,
    val title: String,
    val points: List<CoursePreviewPoint>,
) {
    init {
        require(id.isNotBlank()) { "Invalid course preview ID" }
        require(version > 0) { "Invalid course preview version" }
        require(title.isNotBlank()) { "Invalid course preview title" }
        require(points.size >= 2) { "Course preview needs at least two points" }
        require(points.map { it.id }.distinct().size == points.size) { "Duplicate course preview point ID" }
        val coordinates =
            points.map { point ->
                val latitude = if (point.latitude == 0.0) 0.0 else point.latitude
                val longitude = if (point.longitude == 0.0) 0.0 else point.longitude
                latitude to longitude
            }
        require(coordinates.distinct().size == points.size) { "Duplicate course preview coordinates" }
    }

    val source: CoursePreviewSource get() = CoursePreviewSource.FIXED_OSM_BOUNDING_BOX_CENTERS
}
