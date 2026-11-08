package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class StoredLocationPointTest {
    private val source =
        PracticeSession(
            "source",
            PracticeSelection("goal", "course", 1, 1, "task"),
            "Goal",
            "Course",
            "Task",
            CourseReviewStatus.DEMO,
            100,
            null,
        )
    private val run =
        LocationRecordingRun(
            "run",
            "process",
            source,
            100,
            1,
            200,
            50,
            LocationRecordingRunStatus.STOPPED,
            LocationRecordingStopReason.USER_STOP,
        )

    @Test fun `duplicate coordinates keep separate ordered callback identities around an Empty`() {
        val first = event(1, sample(37.5, 127.1, sdkTime = 900))
        val gap = event(2, LocationUpdateReading.Empty(2))
        val last = event(3, sample(37.5, 127.1, sdkTime = -42, receipt = 3))
        val input = StoredLocationMapInput.capture(run, listOf(first, gap, last), last)
        val features = checkNotNull(StoredLocationPoint.features(input).features())
        assertEquals(listOf(first.id.toString(), last.id.toString()), features.map { it.getStringProperty("event_id") })
        assertEquals(listOf(1, 3), features.map { it.getNumberProperty("ordinal").toInt() })
        assertEquals(1, input.nonSampleCount)
        for (feature in features) {
            assertTrue(feature.geometry() is Point)
            val point = feature.geometry() as Point
            assertEquals(127.1, point.longitude(), 0.0)
            assertEquals(37.5, point.latitude(), 0.0)
            assertEquals(2, point.coordinates().size)
            assertEquals("SDK_UPDATE", feature.getStringProperty("source"))
            assertEquals(run.runId, feature.getStringProperty("run_id"))
        }
        val selected = StoredLocationPoint.selectionFilter(input.selectedSampleId).toArray()
        assertEquals("==", selected[0])
        assertTrue(arrayOf<Any>("get", "event_id").contentDeepEquals(selected[1] as Array<*>))
        assertEquals(last.id.toString(), selected[2])
        val emptySelected = StoredLocationMapInput.capture(run, input.events, gap)
        assertTrue(
            arrayOf<Any>("literal", false).contentDeepEquals(
                StoredLocationPoint.selectionFilter(emptySelected.selectedSampleId).toArray(),
            ),
        )
    }

    @Test fun `zero and negative coordinates remain actual coordinates`() {
        for ((latitude, longitude) in listOf(0.0 to 0.0, -35.0 to -75.0)) {
            val event = event(1, sample(latitude, longitude))
            val input = StoredLocationMapInput.capture(run, listOf(event), event)
            val feature = checkNotNull(StoredLocationPoint.features(input).features()).single()
            val point = feature.geometry() as Point
            assertEquals(latitude, point.latitude(), 0.0)
            assertEquals(longitude, point.longitude(), 0.0)
        }
    }

    @Test fun `the map owns a defensive snapshot and refuses foreign duplicate or changed selections`() {
        val event = event(1, sample(37.5, 127.1))
        val supplied = mutableListOf(event)
        val input = StoredLocationMapInput.capture(run, supplied, event)
        supplied[0] = event.copy(reading = sample(38.0, 128.0))
        assertEquals(listOf(event), input.events)
        assertFalse(input.matches(run, supplied, event))
        assertThrows(UnsupportedOperationException::class.java) { (input.events as MutableList<LocationRecordingEvent>).clear() }
        assertThrows(IllegalArgumentException::class.java) { StoredLocationMapInput.capture(run, listOf(event), supplied[0]) }
        assertThrows(IllegalArgumentException::class.java) {
            StoredLocationMapInput.capture(run, listOf(event.copy(sessionId = "foreign")), event)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StoredLocationMapInput.capture(run, listOf(event, event.copy(ordinal = 2)), event)
        }
        assertThrows(IllegalArgumentException::class.java) { StoredLocationMapInput.capture(run, listOf(event), null) }
    }

    @Test fun `one polar callback makes the whole point source unavailable instead of silently dropping it`() {
        val ordinary = event(1, sample(37.5, 127.1))
        for (latitude in listOf(90.0, -90.0)) {
            val polar = event(2, sample(latitude, 0.0))
            val input = StoredLocationMapInput.capture(run, listOf(ordinary, polar), ordinary)
            assertFalse(StoredLocationPoint.canProject(input))
            assertThrows(IllegalArgumentException::class.java) { StoredLocationPoint.features(input) }
            assertEquals(listOf(ordinary, polar), input.events)
        }
        val edge = event(1, sample(85.0, 0.0))
        assertTrue(StoredLocationPoint.canProject(StoredLocationMapInput.capture(run, listOf(edge), edge)))
    }

    @Test fun `only missing coordinates produce no point or fallback selection`() {
        val empty = event(1, LocationUpdateReading.Empty(1))
        val input = StoredLocationMapInput.capture(run, listOf(empty), empty)
        assertTrue(checkNotNull(StoredLocationPoint.features(input).features()).isEmpty())
        assertEquals(1, input.nonSampleCount)
        assertEquals(null, input.selectedSampleId)
        val noEvents = StoredLocationMapInput.capture(run, emptyList(), null)
        assertTrue(noEvents.samples.isEmpty())
        assertEquals(0, noEvents.nonSampleCount)
    }

    private fun event(
        ordinal: Int,
        reading: LocationUpdateReading,
    ) = LocationRecordingEvent(100L + ordinal, source.sessionId, run.runId, ordinal, 1000L - ordinal, reading)

    private fun sample(
        latitude: Double,
        longitude: Double,
        sdkTime: Long = 1,
        receipt: Long = 1,
    ) = LocationUpdateReading.Sample(LocationUpdateSample(latitude, longitude, 0.0, 0f, 0f, sdkTime, receipt))
}
