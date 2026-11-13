package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
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
    val courseRoads: List<CourseRoadSnapshot>,
) {
    val samples: List<LocationRecordingEvent> = Collections.unmodifiableList(events.filter { it.reading is LocationUpdateReading.Sample })
    val trace by lazy { StoredLocationTrace.features(this) }
    val nonSampleCount: Int get() = events.size - samples.size
    val selectedSampleId: String? get() = selectedEvent?.takeIf { it.reading is LocationUpdateReading.Sample }?.id?.toString()

    fun matches(
        run: LocationRecordingRun,
        events: List<LocationRecordingEvent>,
        selectedEvent: LocationRecordingEvent?,
        courseRoads: List<CourseRoadSnapshot>,
    ): Boolean = this.run == run && this.events == events && this.selectedEvent == selectedEvent && this.courseRoads == courseRoads

    override fun equals(other: Any?): Boolean =
        other is StoredLocationMapInput && matches(other.run, other.events, other.selectedEvent, other.courseRoads)

    override fun hashCode(): Int =
        31 * (31 * (31 * run.hashCode() + events.hashCode()) + (selectedEvent?.hashCode() ?: 0)) + courseRoads.hashCode()

    override fun toString(): String = "StoredLocationMapInput(snapshot=redacted)"

    companion object {
        fun capture(
            run: LocationRecordingRun,
            events: List<LocationRecordingEvent>,
            selectedEvent: LocationRecordingEvent?,
            courseRoads: List<CourseRoadSnapshot> = emptyList(),
        ): StoredLocationMapInput {
            val snapshot = Collections.unmodifiableList(ArrayList(events))
            require(snapshot.size <= LocationRecordingRepository.MAX_EVENTS_PER_RUN) { "Too many stored run events" }
            for ((index, event) in snapshot.withIndex()) {
                require(event.sessionId == run.source.sessionId && event.runId == run.runId) { "Foreign stored run event" }
                require(index == 0 || snapshot[index - 1].id < event.id) { "Invalid stored event order" }
                require(event.ordinal == index + 1) { "Invalid stored event ordinal" }
            }
            val selectedMatches =
                if (snapshot.isEmpty()) {
                    selectedEvent == null
                } else {
                    selectedEvent != null && snapshot.getOrNull(selectedEvent.ordinal - 1) == selectedEvent
                }
            require(selectedMatches) { "Selected event is not the exact stored callback" }
            val roads = Collections.unmodifiableList(courseRoads.map { it.copy(nodes = Collections.unmodifiableList(ArrayList(it.nodes))) })
            require(roads.map { it.id }.distinct().size == roads.size) { "Duplicate course road candidate" }
            return StoredLocationMapInput(run, snapshot, selectedEvent, roads)
        }
    }
}
