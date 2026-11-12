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

    val gwanghwamun =
        CourseRoadSnapshot(
            id = "osm-way-66662159-v10",
            title = "새문안로5길",
            highway = "residential",
            osmWayId = 66662159L,
            osmWayVersion = 10,
            capturedAtEpochMillis = 1794322425829L,
            nodes =
                listOf(
                    CourseRoadSnapshot.Node(436850153L, 9, 37.5740915, 126.9744747),
                    CourseRoadSnapshot.Node(11377834311L, 1, 37.5744203, 126.9744381),
                    CourseRoadSnapshot.Node(11377834303L, 1, 37.5753656, 126.9743277),
                    CourseRoadSnapshot.Node(11202943789L, 2, 37.5754772, 126.9743147),
                    CourseRoadSnapshot.Node(11202943791L, 2, 37.5755187, 126.9743099),
                    CourseRoadSnapshot.Node(436850150L, 6, 37.5756733, 126.9742918),
                ),
        )

    // Copyright OpenStreetMap contributors, ODbL 1.0.
    // https://api.openstreetmap.org/api/0.6/way/619101472/5.json
    // Independent public road snapshot. The current access and Pleos guidance path are unverified.
    val pangyoWaypoint =
        CourseRoadSnapshot(
            id = "osm-way-619101472-v5",
            title = "창업로40번길",
            highway = "residential",
            osmWayId = 619101472L,
            osmWayVersion = 5,
            capturedAtEpochMillis = 1794440075031L,
            nodes =
                listOf(
                    CourseRoadSnapshot.Node(5785292092L, 3, 37.4107925, 127.0948633),
                    CourseRoadSnapshot.Node(14081613437L, 2, 37.4108116, 127.0946083),
                    CourseRoadSnapshot.Node(14084804748L, 1, 37.4108137, 127.0945693),
                    CourseRoadSnapshot.Node(14081613445L, 2, 37.4108163, 127.0945144),
                    CourseRoadSnapshot.Node(14081613438L, 2, 37.4108204, 127.0944642),
                    CourseRoadSnapshot.Node(14081613444L, 1, 37.4108273, 127.0944065),
                    CourseRoadSnapshot.Node(14081613436L, 1, 37.4108413, 127.0943284),
                    CourseRoadSnapshot.Node(5850817444L, 2, 37.4108636, 127.0942273),
                    CourseRoadSnapshot.Node(14081613443L, 1, 37.4108929, 127.0941207),
                    CourseRoadSnapshot.Node(5850817406L, 2, 37.4109122, 127.0940621),
                    CourseRoadSnapshot.Node(14081613442L, 1, 37.4109331, 127.0940148),
                    CourseRoadSnapshot.Node(14081613441L, 1, 37.4109541, 127.0939743),
                    CourseRoadSnapshot.Node(14081613440L, 1, 37.4109864, 127.0939267),
                    CourseRoadSnapshot.Node(14081613439L, 1, 37.4112145, 127.0936002),
                    CourseRoadSnapshot.Node(14081613446L, 1, 37.4112843, 127.0934963),
                    CourseRoadSnapshot.Node(14081613452L, 1, 37.4114015, 127.0933045),
                    CourseRoadSnapshot.Node(14081613451L, 1, 37.4114194, 127.0932782),
                    CourseRoadSnapshot.Node(14081613450L, 1, 37.4114463, 127.0932455),
                    CourseRoadSnapshot.Node(14081613449L, 1, 37.4114745, 127.0932153),
                    CourseRoadSnapshot.Node(14081613453L, 1, 37.4114906, 127.0932006),
                    CourseRoadSnapshot.Node(14081613448L, 1, 37.4115056, 127.0931871),
                    CourseRoadSnapshot.Node(14081613447L, 1, 37.4115421, 127.0931627),
                    CourseRoadSnapshot.Node(14081613457L, 1, 37.4116208, 127.0931224),
                    CourseRoadSnapshot.Node(14081613456L, 1, 37.4116475, 127.0931104),
                    CourseRoadSnapshot.Node(14081613455L, 1, 37.4116789, 127.0931008),
                    CourseRoadSnapshot.Node(14081613454L, 1, 37.4117096, 127.0930944),
                    CourseRoadSnapshot.Node(14081613458L, 1, 37.4117465, 127.0930891),
                    CourseRoadSnapshot.Node(14081613459L, 1, 37.4117700, 127.0930881),
                    CourseRoadSnapshot.Node(14081613460L, 1, 37.4117931, 127.0930892),
                    CourseRoadSnapshot.Node(14081613461L, 1, 37.4118213, 127.0930916),
                    CourseRoadSnapshot.Node(5850817408L, 2, 37.4118498, 127.0930967),
                    CourseRoadSnapshot.Node(14081613462L, 1, 37.4118737, 127.0931027),
                    CourseRoadSnapshot.Node(14081613463L, 1, 37.4119014, 127.0931110),
                    CourseRoadSnapshot.Node(14081613465L, 1, 37.4119312, 127.0931224),
                    CourseRoadSnapshot.Node(14081613466L, 1, 37.4119675, 127.0931373),
                    CourseRoadSnapshot.Node(14081613467L, 1, 37.4119980, 127.0931515),
                    CourseRoadSnapshot.Node(14081613469L, 1, 37.4120159, 127.0931603),
                    CourseRoadSnapshot.Node(14081613468L, 1, 37.4120362, 127.0931719),
                    CourseRoadSnapshot.Node(5850817409L, 2, 37.4120621, 127.0931903),
                    CourseRoadSnapshot.Node(14081613470L, 1, 37.4120882, 127.0932112),
                    CourseRoadSnapshot.Node(14081613471L, 1, 37.4121197, 127.0932390),
                    CourseRoadSnapshot.Node(14081613472L, 1, 37.4121524, 127.0932713),
                    CourseRoadSnapshot.Node(14081613473L, 1, 37.4121787, 127.0932981),
                    CourseRoadSnapshot.Node(5850817423L, 2, 37.4122059, 127.0933281),
                    CourseRoadSnapshot.Node(14081613474L, 1, 37.4122283, 127.0933563),
                    CourseRoadSnapshot.Node(14081613475L, 1, 37.4122497, 127.0933852),
                    CourseRoadSnapshot.Node(14081613476L, 1, 37.4122696, 127.0934135),
                    CourseRoadSnapshot.Node(14081613477L, 1, 37.4122863, 127.0934406),
                    CourseRoadSnapshot.Node(14081613478L, 1, 37.4123029, 127.0934713),
                    CourseRoadSnapshot.Node(14081613479L, 1, 37.4123179, 127.0935012),
                    CourseRoadSnapshot.Node(14081613480L, 1, 37.4123334, 127.0935326),
                    CourseRoadSnapshot.Node(14081613481L, 1, 37.4123500, 127.0935689),
                    CourseRoadSnapshot.Node(5850817410L, 2, 37.4123665, 127.0936044),
                    CourseRoadSnapshot.Node(5850817424L, 2, 37.4124143, 127.0937125),
                    CourseRoadSnapshot.Node(5850817425L, 2, 37.4124909, 127.0938837),
                    CourseRoadSnapshot.Node(5850817419L, 2, 37.4126072, 127.0941395),
                    CourseRoadSnapshot.Node(5850817422L, 2, 37.4126413, 127.0942155),
                    CourseRoadSnapshot.Node(5850817427L, 2, 37.4126690, 127.0942869),
                    CourseRoadSnapshot.Node(5850817411L, 2, 37.4126962, 127.0943601),
                    CourseRoadSnapshot.Node(14081613500L, 1, 37.4127069, 127.0944013),
                    CourseRoadSnapshot.Node(5850817418L, 2, 37.4127145, 127.0944425),
                    CourseRoadSnapshot.Node(14081613501L, 1, 37.4127188, 127.0944854),
                    CourseRoadSnapshot.Node(5850817428L, 2, 37.4127231, 127.0945359),
                    CourseRoadSnapshot.Node(5850817414L, 2, 37.4127238, 127.0945930),
                    CourseRoadSnapshot.Node(14081613502L, 1, 37.4127233, 127.0946550),
                    CourseRoadSnapshot.Node(5850817420L, 2, 37.4127173, 127.0947090),
                    CourseRoadSnapshot.Node(14081613503L, 1, 37.4127111, 127.0947516),
                    CourseRoadSnapshot.Node(5850817415L, 2, 37.4126995, 127.0947994),
                    CourseRoadSnapshot.Node(5850817421L, 2, 37.4126786, 127.0948755),
                    CourseRoadSnapshot.Node(14081613504L, 1, 37.4126644, 127.0949111),
                    CourseRoadSnapshot.Node(5850817412L, 2, 37.4126448, 127.0949482),
                    CourseRoadSnapshot.Node(5850817416L, 2, 37.4126005, 127.0950242),
                    CourseRoadSnapshot.Node(5850817417L, 2, 37.4125500, 127.0951028),
                    CourseRoadSnapshot.Node(5850817413L, 2, 37.4124724, 127.0952163),
                    CourseRoadSnapshot.Node(14081613499L, 1, 37.4122801, 127.0954777),
                    CourseRoadSnapshot.Node(5850817399L, 2, 37.4121218, 127.0956758),
                ),
        )
}
