package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeNavigationRoute
import io.github.hangillab.hangil.core.domain.PracticeSession
import java.util.Collections

/** Display-only points and fixed road shapes, never an observed or reviewed driving route. */
@ConsistentCopyVisibility
data class MapPreview internal constructor(
    val id: String,
    val version: Int,
    val title: String,
    val points: List<Point>,
    val source: Source,
    val roads: List<CourseRoadSnapshot> = emptyList(),
) {
    val sourceCode: String
        get() =
            when (source) {
                is Source.FixedOsm -> "FIXED_OSM"
                is Source.NavigationRequest -> "NAVIGATION_REQUEST"
                is Source.FixedRoads -> "SESSION_PUBLIC_ROADS"
            }

    @ConsistentCopyVisibility
    data class Point internal constructor(
        val id: String,
        val title: String,
        val latitude: Double,
        val longitude: Double,
        val role: Role = Role.LANDMARK,
    )

    enum class Role { LANDMARK, WAYPOINT, DESTINATION }

    sealed interface Source {
        @ConsistentCopyVisibility
        data class FixedOsm internal constructor(
            val preview: CourseMapPreview,
        ) : Source

        @ConsistentCopyVisibility
        data class NavigationRequest internal constructor(
            val courseId: String,
            val courseVersion: Int,
            val briefingVersion: Int,
            val route: PracticeNavigationRoute,
        ) : Source

        @ConsistentCopyVisibility
        data class FixedRoads internal constructor(
            val session: PracticeSession,
        ) : Source
    }

    companion object {
        internal fun from(
            source: PracticeSession,
            roads: List<CourseRoadSnapshot>,
        ): MapPreview {
            require(!source.isActive) { "A review road map requires a finished session" }
            require(roads.isNotEmpty()) { "A review road map requires public road snapshots" }
            val snapshots = frozen(roads.map { it.copy(nodes = frozen(it.nodes)) })
            require(snapshots.map { it.id }.distinct().size == snapshots.size) { "Duplicate road preview ID" }
            return MapPreview(
                id = "session-public-roads",
                version = 1,
                title = source.courseTitle,
                points = emptyList(),
                source = Source.FixedRoads(source),
                roads = snapshots,
            )
        }

        internal fun from(course: PracticeCourse): MapPreview? {
            val roads = frozen(course.roadSnapshots.map { it.copy(nodes = frozen(it.nodes)) })
            require(roads.map { it.id }.distinct().size == roads.size) { "Duplicate road preview ID" }
            course.mapPreview?.let { return from(it).copy(roads = roads) }
            val route = course.navigationRoute ?: return null
            val snapshot = route.copy(waypoints = frozen(route.waypoints))
            return MapPreview(
                id = "${course.id}-navigation-request-pois",
                version = course.version,
                title = course.title,
                points =
                    frozen(
                        snapshot.waypoints.map { it.displayPoint(Role.WAYPOINT) } +
                            snapshot.destination.displayPoint(Role.DESTINATION),
                    ),
                source = Source.NavigationRequest(course.id, course.version, course.briefingVersion, snapshot),
                roads = roads,
            )
        }

        internal fun from(preview: CourseMapPreview): MapPreview {
            val snapshot = preview.copy(points = frozen(preview.points))
            return MapPreview(
                id = snapshot.id,
                version = snapshot.version,
                title = snapshot.title,
                points = frozen(snapshot.points.map { Point(it.id, it.title, it.latitude, it.longitude) }),
                source = Source.FixedOsm(snapshot),
            )
        }

        private fun PracticeNavigationRoute.Point.displayPoint(role: Role): Point =
            Point(
                id = "poi:${poiId.length}:$poiId${poiSubId.length}:$poiSubId",
                title = name,
                latitude = latitude,
                longitude = longitude,
                role = role,
            )

        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
}
