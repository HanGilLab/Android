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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
