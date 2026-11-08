package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionReviewSampleTest {
    @Test fun `sample identity is full event and immutable owner rather than equal coordinates or terminal metadata`() {
        val source =
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
        val run = LocationRecordingRun("run", "private-process", source, 101, 10, null, null, LocationRecordingRunStatus.OPEN, null)
        val sample = LocationUpdateSample(-0.0, 127.0, -2.0, 421f, -5f, -42, 11)
        val event = LocationRecordingEvent(1, source.sessionId, run.runId, 1, 102, LocationUpdateReading.Sample(sample))
        val anchor = SessionReviewSample.from(run, event)
        assertEquals(
            anchor,
            SessionReviewSample.from(
                run.copy(
                    status = LocationRecordingRunStatus.STOPPED,
                    stopReason = LocationRecordingStopReason.USER_STOP,
                    endedAtEpochMillis = 103,
                    endedAtElapsedRealtimeMillis = 12,
                ),
                event,
            ),
        )
        assertNotEquals(anchor, SessionReviewSample.from(run, event.copy(id = 2, ordinal = 2)))
        assertThrows(IllegalArgumentException::class.java) { SessionReviewSample.from(run, event.copy(runId = "other")) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { SessionReviewSample.from(run, event.copy(reading = LocationUpdateReading.Empty(11))) }
        assertFalse(anchor.toString().contains("private-process"))
        assertFalse(anchor.toString().contains("127"))
        assertEquals(sample, anchor.sample)
    }
}
