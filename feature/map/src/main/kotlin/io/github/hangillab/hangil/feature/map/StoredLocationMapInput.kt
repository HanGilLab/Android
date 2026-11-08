package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import java.util.Collections

/** The renderer owns an immutable copy, without sorting, deduplication or filling missing readings. */
internal class StoredLocationMapInput private constructor(
    val run: LocationRecordingRun,
    val events: List<LocationRecordingEvent>,
    val selectedEvent: LocationRecordingEvent?,
) {
    val samples: List<LocationRecordingEvent> = Collections.unmodifiableList(events.filter { it.reading is LocationUpdateReading.Sample })
    val nonSampleCount: Int get() = events.size - samples.size
    val selectedSampleId: String? get() = selectedEvent?.takeIf { it.reading is LocationUpdateReading.Sample }?.id?.toString()

    fun matches(
        run: LocationRecordingRun,
        events: List<LocationRecordingEvent>,
        selectedEvent: LocationRecordingEvent?,
    ): Boolean = this.run == run && this.events == events && this.selectedEvent == selectedEvent

    override fun equals(other: Any?): Boolean = other is StoredLocationMapInput && matches(other.run, other.events, other.selectedEvent)

    override fun hashCode(): Int = 31 * (31 * run.hashCode() + events.hashCode()) + (selectedEvent?.hashCode() ?: 0)

    override fun toString(): String = "StoredLocationMapInput(snapshot=redacted)"

    companion object {
        fun capture(
            run: LocationRecordingRun,
            events: List<LocationRecordingEvent>,
            selectedEvent: LocationRecordingEvent?,
        ): StoredLocationMapInput {
            val snapshot = Collections.unmodifiableList(ArrayList(events))
            require(snapshot.size <= LocationRecordingRepository.MAX_EVENTS_PER_RUN) { "Too many stored run events" }
            require(snapshot.all { it.sessionId == run.source.sessionId && it.runId == run.runId }) { "Foreign stored run event" }
            require(snapshot.zipWithNext().all { (before, after) -> before.id < after.id }) { "Invalid stored event order" }
            require(snapshot.withIndex().all { (index, event) -> event.ordinal == index + 1 }) { "Invalid stored event ordinal" }
            val selectedMatches =
                if (snapshot.isEmpty()) {
                    selectedEvent == null
                } else {
                    selectedEvent != null && snapshot.singleOrNull { it.id == selectedEvent.id } == selectedEvent
                }
            require(selectedMatches) { "Selected event is not the exact stored callback" }
            return StoredLocationMapInput(run, snapshot, selectedEvent)
        }
    }
}
