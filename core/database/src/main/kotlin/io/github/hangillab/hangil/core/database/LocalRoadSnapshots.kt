package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot

/**
 * Road data literals: Copyright OpenStreetMap contributors, ODbL 1.0.
 * https://www.openstreetmap.org/copyright
 * https://opendatacommons.org/licenses/odbl/1-0/
 * Way and individual node versions were independently read from the official API.
 * Not current traffic/access information, a recommended route or an observed trip.
 */
internal object LocalRoadSnapshots {
    val cityHall =
        listOf(
            CourseRoadSnapshot(
                id = "osm-way-184443139-v13",
                title = "세종대로20길",
                highway = "secondary",
                osmWayId = 184443139L,
                osmWayVersion = 13,
                capturedAtEpochMillis = 1794085063034L,
                nodes =
                    listOf(
                        CourseRoadSnapshot.Node(436877003L, 5, 37.5669982, 126.9791977),
                        CourseRoadSnapshot.Node(6107335405L, 3, 37.5670067, 126.9781642),
                        CourseRoadSnapshot.Node(3825649163L, 2, 37.567002, 126.9776012),
                        CourseRoadSnapshot.Node(11414286297L, 2, 37.5670008, 126.9773713),
                        CourseRoadSnapshot.Node(13721232746L, 1, 37.5670016, 126.9773121),
                        CourseRoadSnapshot.Node(5851251264L, 2, 37.5670025, 126.9772406),
                    ),
            ),
            CourseRoadSnapshot(
                id = "osm-way-431903253-v5",
                title = "세종대로19길",
                highway = "residential",
                osmWayId = 431903253L,
                osmWayVersion = 5,
                capturedAtEpochMillis = 1794085063770L,
                nodes =
                    listOf(
                        CourseRoadSnapshot.Node(5851251266L, 5, 37.5666372, 126.9771167),
                        CourseRoadSnapshot.Node(11414286332L, 2, 37.5666376, 126.9769246),
                        CourseRoadSnapshot.Node(5633956118L, 2, 37.5666061, 126.9762636),
                        CourseRoadSnapshot.Node(5633956117L, 2, 37.5666068, 126.9758739),
                        CourseRoadSnapshot.Node(4311362735L, 3, 37.5666055, 126.9756615),
                        CourseRoadSnapshot.Node(7176684827L, 1, 37.5666173, 126.9754963),
                        CourseRoadSnapshot.Node(4311362736L, 2, 37.5666261, 126.9754505),
                    ),
            ),
        )
}
