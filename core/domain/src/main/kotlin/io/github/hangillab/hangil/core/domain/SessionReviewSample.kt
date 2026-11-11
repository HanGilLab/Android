package io.github.hangillab.hangil.core.domain

/** A user-linked stored callback and immutable run owner, never an automatic road or time match. */
class SessionReviewSample(
    val event: LocationRecordingEvent,
    val processId: String,
    val startedAtEpochMillis: Long,
    val startedAtElapsedRealtimeMillis: Long,
    val roadSection: CourseRoadSection? = null,
) {
    val origin: String get() = "USER_LINKED_STORED_SAMPLE"
    val sample: LocationUpdateSample get() = (event.reading as LocationUpdateReading.Sample).sample

    init {
        require(processId.isNotBlank() && startedAtEpochMillis > 0 && startedAtElapsedRealtimeMillis >= 0) { "Invalid linked sample owner" }
        require(
            event.reading is LocationUpdateReading.Sample &&
                event.reading.receivedAtElapsedRealtimeMillis >= startedAtElapsedRealtimeMillis,
        ) {
            "A linked sample needs an actual stored callback payload"
        }
    }

    /** Compares the immutable callback and run owner, independent of the public road choice. */
    fun sameCallback(other: SessionReviewSample): Boolean =
        event == other.event && processId == other.processId &&
            startedAtEpochMillis == other.startedAtEpochMillis && startedAtElapsedRealtimeMillis == other.startedAtElapsedRealtimeMillis

    fun withRoadSection(section: CourseRoadSection?): SessionReviewSample =
        SessionReviewSample(event, processId, startedAtEpochMillis, startedAtElapsedRealtimeMillis, section)

    override fun equals(other: Any?): Boolean = other is SessionReviewSample && sameCallback(other) && roadSection == other.roadSection

    override fun hashCode(): Int =
        31 * (
            31 * (31 * (31 * event.hashCode() + processId.hashCode()) + startedAtEpochMillis.hashCode()) +
                startedAtElapsedRealtimeMillis.hashCode()
        ) + (roadSection?.hashCode() ?: 0)

    override fun toString(): String = "SessionReviewSample(origin=$origin, snapshot=redacted)"

    companion object {
        fun from(
            run: LocationRecordingRun,
            event: LocationRecordingEvent,
        ): SessionReviewSample {
            require(run.runId == event.runId && run.source.sessionId == event.sessionId) { "Linked sample belongs to another run" }
            return SessionReviewSample(event, run.processId, run.startedAtEpochMillis, run.startedAtElapsedRealtimeMillis)
        }
    }
}
