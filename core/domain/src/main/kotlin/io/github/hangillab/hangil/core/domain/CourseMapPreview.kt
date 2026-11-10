package io.github.hangillab.hangil.core.domain

/** Landmarks and an optional public road in study order, without live routing or travel evidence. */
data class CourseMapPreview(
    val id: String,
    val version: Int,
    val title: String,
    val points: List<CoursePreviewPoint>,
    val practiceRoadId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Invalid course preview ID" }
        require(version > 0) { "Invalid course preview version" }
        require(title.isNotBlank()) { "Invalid course preview title" }
        require(practiceRoadId == null || practiceRoadId.isNotBlank()) { "Invalid practice road ID" }
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
