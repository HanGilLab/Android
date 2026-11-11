package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class StoredLocationRoadSuggestionTest {
    private val run =
        LocationRecordingRun(
            "run",
            "process",
            PracticeSession(
                "source",
                PracticeSelection("goal", "course", 1, 1, "task"),
                "Goal",
                "Course",
                "Task",
                CourseReviewStatus.DEMO,
                100,
                null,
            ),
            100,
            1,
            100_000,
            100_000,
            LocationRecordingRunStatus.STOPPED,
            LocationRecordingStopReason.USER_STOP,
        )
    private val nearRoad = road(1, BASE_LATITUDE)
    private val otherRoad = road(2, BASE_LATITUDE - 0.00050)

    @Test fun `three adjacent moving samples suggest the same unique course road`() {
        val captured = input(movingSamples(), roads = listOf(otherRoad, nearRoad))
        assertEquals(nearRoad, StoredLocationRoadSuggestion.find(captured))
    }

    @Test fun `an empty callback separates the samples and cannot be a selection`() {
        val (first, second, third) = movingSamples()
        val interrupted = listOf(first, LocationUpdateReading.Empty(1_500), second, third)
        assertNull(StoredLocationRoadSuggestion.find(input(interrupted, selectedIndex = 2)))
        val continuousElsewhere = listOf(LocationUpdateReading.Empty(500), first, second, third)
        assertNull(StoredLocationRoadSuggestion.find(input(continuousElsewhere, selectedIndex = 0)))
    }

    @Test fun `the no update timeout prevents bridging otherwise near samples`() {
        val timeout = LocationUpdateGateway.NO_UPDATE_TIMEOUT_MILLIS
        val interrupted =
            listOf(
                sample(0.0, 1_000),
                sample(0.00015, 1_000 + timeout),
                sample(0.00030, 2_000 + timeout),
            )
        assertNull(StoredLocationRoadSuggestion.find(input(interrupted)))
    }

    @Test fun `adjacent samples must agree on the same nearest road`() {
        val (first, second) = movingSamples()
        val switched = listOf(first, second, sample(0.00030, 3_000, BASE_LATITUDE - 0.00046))
        assertNull(StoredLocationRoadSuggestion.find(input(switched)))
    }

    @Test fun `a narrow lead and an exact tie both leave the choice to the user`() {
        val closeRoad = road(3, BASE_LATITUDE + 0.00015)
        val sameShape = road(4, BASE_LATITUDE)
        assertNull(StoredLocationRoadSuggestion.find(input(movingSamples(), roads = listOf(nearRoad, closeRoad))))
        assertNull(StoredLocationRoadSuggestion.find(input(movingSamples(), roads = listOf(nearRoad, sameShape))))
    }

    @Test fun `distance from the road and small stationary jitter prevent a suggestion`() {
        assertNull(StoredLocationRoadSuggestion.find(input(movingSamples(BASE_LATITUDE + 0.00030))))
        val jitter = listOf(sample(0.0, 1_000), sample(0.00001, 2_000), sample(0.00002, 3_000))
        assertNull(StoredLocationRoadSuggestion.find(input(jitter)))
    }

    @Test fun `a single sample still supports distance comparison without a suggestion`() {
        val point = sample(0.00015, 2_000)
        val distance = checkNotNull(StoredLocationRoadDistance.meters(point.sample, nearRoad))
        val otherDistance = checkNotNull(StoredLocationRoadDistance.meters(point.sample, otherRoad))
        assertTrue(distance < otherDistance)
        assertNull(StoredLocationRoadSuggestion.find(input(listOf(point), selectedIndex = 0)))
    }

    @Test fun `the linked section follows a bend and excludes the rest of the road in both directions`() {
        val bend = CourseRoadSnapshot.Node(12, 1, BASE_LATITUDE, BASE_LONGITUDE + 0.00015)
        val curved =
            nearRoad.copy(
                nodes =
                    listOf(
                        nearRoad.nodes.first(),
                        bend,
                        CourseRoadSnapshot.Node(13, 1, BASE_LATITUDE + 0.001, bend.longitude),
                    ),
            )
        val readings =
            listOf(
                sample(-0.00015, 1_000, BASE_LATITUDE + 0.00002),
                sample(0.00005, 2_000, BASE_LATITUDE + 0.00002),
                sample(0.00017, 3_000, BASE_LATITUDE + 0.00040),
            )
        val forward = input(readings, roads = listOf(curved, otherRoad))
        val reversed =
            input(
                readings.reversed().mapIndexed { index, reading ->
                    LocationUpdateReading.Sample(reading.sample.copy(receivedAtElapsedRealtimeMillis = 1_000L * (index + 1)))
                },
                roads = listOf(curved, otherRoad),
            )
        val section = checkNotNull(StoredLocationRoadSuggestion.section(forward, linkedReview(forward, curved)))
        val points = section.coordinates()
        assertEquals(3, points.size)
        assertEquals(Point.fromLngLat(bend.longitude, bend.latitude), points[1])
        assertEquals(readings.first().sample.longitude, points.first().longitude(), 0.00001)
        assertEquals(readings.last().sample.latitude, points.last().latitude(), 0.00001)
        assertEquals(section, StoredLocationRoadSuggestion.section(reversed, linkedReview(reversed, curved)))
    }

    @Test fun `nearby points do not highlight a section across a gap or direction reversal`() {
        val (first, second, third) = movingSamples()
        val gap = input(listOf(first, LocationUpdateReading.Empty(1_500), second, third), selectedIndex = 2)
        assertNull(StoredLocationRoadSuggestion.section(gap, linkedReview(gap)))
        val reversed = input(listOf(first, second, sample(0.00005, 3_000), sample(0.00040, 4_000)))
        assertEquals(nearRoad, StoredLocationRoadSuggestion.find(reversed))
        assertNull(StoredLocationRoadSuggestion.section(reversed, linkedReview(reversed)))
    }

    @Test fun `a shared node on a straight road is not a second route`() {
        val intermediate = CourseRoadSnapshot.Node(12, 1, BASE_LATITUDE, BASE_LONGITUDE + 0.00050)
        val split = nearRoad.copy(nodes = listOf(nearRoad.nodes.first(), intermediate, nearRoad.nodes.last()))
        val latitude = BASE_LATITUDE + 0.00010
        val readings = listOf(sample(-0.00005, 1_000, latitude), sample(0.00010, 2_000, latitude), sample(0.00025, 3_000, latitude))
        val captured = input(readings, roads = listOf(split))
        val section = checkNotNull(StoredLocationRoadSuggestion.section(captured, linkedReview(captured, split)))
        assertEquals(readings.last().sample.longitude, section.coordinates().last().longitude(), 0.00001)
    }

    @Test fun `two nearby arms of the same road cannot resolve a section`() {
        val first = nearRoad.nodes.first()
        val last = nearRoad.nodes.last()
        val folded =
            nearRoad.copy(
                nodes =
                    nearRoad.nodes +
                        listOf(
                            last.copy(osmNodeId = 12, latitude = BASE_LATITUDE + 0.00010),
                            first.copy(osmNodeId = 13, latitude = BASE_LATITUDE + 0.00010),
                        ),
            )
        val captured = input(movingSamples(), roads = listOf(folded))
        assertEquals(folded, StoredLocationRoadSuggestion.find(captured))
        assertNull(StoredLocationRoadSuggestion.section(captured, linkedReview(captured, folded)))
    }

    @Test fun `only the exact linked sample and saved road can disclose the section`() {
        val readings = movingSamples()
        val captured = input(readings)
        assertNull(StoredLocationRoadSuggestion.section(input(readings, selectedIndex = 2), linkedReview(captured)))
        assertNull(StoredLocationRoadSuggestion.section(captured, linkedReview(captured, otherRoad)))
    }

    private fun linkedReview(
        input: StoredLocationMapInput,
        road: CourseRoadSnapshot = nearRoad,
    ) = SessionReviewRoad(
        input.run.source.copy(endedAtEpochMillis = 2_000),
        road,
        "version",
        sampleAnchor = SessionReviewSample.from(input.run, checkNotNull(input.selectedEvent)),
    )

    private fun input(
        readings: List<LocationUpdateReading>,
        selectedIndex: Int = 1,
        roads: List<CourseRoadSnapshot> = listOf(nearRoad, otherRoad),
    ): StoredLocationMapInput {
        val events =
            readings.mapIndexed { index, reading ->
                LocationRecordingEvent(index + 1L, run.source.sessionId, run.runId, index + 1, 1_000L + index, reading)
            }
        return StoredLocationMapInput.capture(run, events, events[selectedIndex], roads)
    }

    private fun movingSamples(latitude: Double = BASE_LATITUDE + 0.00004) =
        listOf(sample(0.0, 1_000, latitude), sample(0.00015, 2_000, latitude), sample(0.00030, 3_000, latitude))

    private fun sample(
        longitudeOffset: Double,
        receipt: Long,
        latitude: Double = BASE_LATITUDE + 0.00004,
    ) = LocationUpdateReading.Sample(LocationUpdateSample(latitude, BASE_LONGITUDE + longitudeOffset, 0.0, 0f, 0f, -42, receipt))

    private fun road(
        id: Long,
        latitude: Double,
    ) = CourseRoadSnapshot(
        "road-$id",
        "Road $id",
        "residential",
        id,
        1,
        1,
        listOf(
            CourseRoadSnapshot.Node(id * 10, 1, latitude, BASE_LONGITUDE - 0.001),
            CourseRoadSnapshot.Node(id * 10 + 1, 1, latitude, BASE_LONGITUDE + 0.001),
        ),
    )

    private companion object {
        const val BASE_LATITUDE = 37.50
        const val BASE_LONGITUDE = 127.10
    }
}
