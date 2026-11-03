package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUpdateModelsTest {
    @Test fun `update preserves raw meaning and redacts payload strings`() {
        val sample = sample().copy(sdkTimestampMillis = Long.MIN_VALUE, headingRaw = -900f, confidenceRaw = -3f)
        assertEquals(LocationUpdateSource.SDK_UPDATE, sample.source)
        assertEquals(Long.MIN_VALUE, sample.sdkTimestampMillis)
        assertEquals(-900f, sample.headingRaw)
        assertEquals(-3f, sample.confidenceRaw)
        assertFalse(sample.toString().contains(sample.latitude.toString()))
        assertFalse(LocationUpdateReading.Sample(sample).toString().contains(sample.longitude.toString()))
        assertTrue(sample.toString().contains("redacted"))
        assertEquals(LocationSource.VEHICLE_SDK_LAST_KNOWN, LastKnownLocation(1.0, 2.0, 3.0, 4f, 5f, 6, 7).source)
    }

    @Test fun `invalid structure is rejected without inventing coordinates`() {
        assertThrows(IllegalArgumentException::class.java) { sample().copy(latitude = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(longitude = 181.0) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(altitudeRaw = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(confidenceRaw = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(receivedAtElapsedRealtimeMillis = -1) }
    }

    @Test fun `adapter failures and SDK errors keep distinct class only origins`() {
        val callback = LocationUpdateReading.Error(LocationUpdateErrorOrigin.SDK_CALLBACK, IllegalStateException::class.java.name, 10)
        val adapter = callback.copy(origin = LocationUpdateErrorOrigin.ADAPTER)
        assertFalse(callback == adapter)
        assertEquals(10L, adapter.receivedAtElapsedRealtimeMillis)
        assertThrows(IllegalArgumentException::class.java) { callback.copy(exceptionClassName = "message with spaces") }
        assertThrows(IllegalArgumentException::class.java) { LocationUpdateReading.NoUpdate(-1) }
        assertThrows(IllegalArgumentException::class.java) { LocationUpdateReading.PermissionDenied(-1) }
    }

    @Test fun `run markers preserve positive wall reversal but reject partial or regressed monotonic ends`() {
        val source =
            PracticeSession(
                "fixture",
                PracticeSelection("goal", "course", 1, 1, "task"),
                "Goal",
                "Course",
                "Task",
                CourseReviewStatus.DEMO,
                1_000,
                null,
            )
        val run = LocationRecordingRun("run", "process", source, 500, 100, null, null, LocationRecordingRunStatus.OPEN, null)
        val ended =
            run.copy(
                status = LocationRecordingRunStatus.STOPPED,
                stopReason = LocationRecordingStopReason.USER_STOP,
                endedAtEpochMillis = 200,
                endedAtElapsedRealtimeMillis = 101,
            )
        assertEquals(200L, ended.endedAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) { ended.copy(endedAtElapsedRealtimeMillis = 99) }
        assertThrows(IllegalArgumentException::class.java) { ended.copy(endedAtEpochMillis = null) }
        assertThrows(IllegalArgumentException::class.java) { ended.copy(status = LocationRecordingRunStatus.INTERRUPTED) }
        val interrupted =
            run.copy(
                status = LocationRecordingRunStatus.INTERRUPTED,
                stopReason = LocationRecordingStopReason.PROCESS_INTERRUPTED,
            )
        assertEquals(null, interrupted.endedAtEpochMillis)
        assertFalse(interrupted.toString().contains("fixture"))
    }

    private fun sample() = LocationUpdateSample(12.25, 34.75, 6.0, 7f, 8f, 9, 10)
}
