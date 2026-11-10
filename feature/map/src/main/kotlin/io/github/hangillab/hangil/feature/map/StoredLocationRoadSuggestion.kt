package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfConstants.UNIT_METRES
import org.maplibre.turf.TurfMeasurement

/** Suggests a nearby registered road for review, without inferring a driven path. */
internal object StoredLocationRoadSuggestion {
    private const val NEIGHBORS_PER_SIDE = 2
    private const val MIN_SAMPLES = 3
    private const val MIN_SPAN_METERS = 20.0
    private const val MAX_ROAD_DISTANCE_METERS = 25.0
    private const val MIN_RUNNER_UP_GAP_METERS = 15.0

    fun find(input: StoredLocationMapInput): CourseRoadSnapshot? {
        val selectedEvent = input.selectedEvent ?: return null
        val selected = (selectedEvent.reading as? LocationUpdateReading.Sample)?.sample ?: return null
        if (input.courseRoads.isEmpty()) return null

        val index = selectedEvent.ordinal - 1
        val samples = mutableListOf<LocationUpdateSample>()
        var last = selected
        for (neighbor in index - 1 downTo maxOf(0, index - NEIGHBORS_PER_SIDE)) {
            val previous = (input.events[neighbor].reading as? LocationUpdateReading.Sample)?.sample ?: break
            if (!StoredLocationTrace.canLink(input.run, previous, last)) break
            samples.add(previous)
            last = previous
        }
        samples.reverse()
        samples.add(selected)
        last = selected
        for (neighbor in index + 1..minOf(input.events.lastIndex, index + NEIGHBORS_PER_SIDE)) {
            val next = (input.events[neighbor].reading as? LocationUpdateReading.Sample)?.sample ?: break
            if (!StoredLocationTrace.canLink(input.run, last, next)) break
            samples.add(next)
            last = next
        }
        if (samples.size < MIN_SAMPLES) return null
        val span = spanMeters(samples.first(), samples.last())
        if (!span.isFinite() || span < MIN_SPAN_METERS) return null

        val nearest = nearestRoad(samples.first(), input.courseRoads) ?: return null
        return nearest.takeIf { road -> samples.drop(1).all { nearestRoad(it, input.courseRoads) == road } }
    }

    private fun nearestRoad(
        sample: LocationUpdateSample,
        roads: List<CourseRoadSnapshot>,
    ): CourseRoadSnapshot? {
        val distances =
            roads
                .map { road ->
                    road to (StoredLocationRoadDistance.meters(sample, road) ?: return null)
                }.sortedBy { it.second }
        val (nearest, meters) = distances.first()
        val runnerUp = distances.getOrNull(1)?.second
        return nearest.takeIf { meters <= MAX_ROAD_DISTANCE_METERS && (runnerUp == null || runnerUp - meters >= MIN_RUNNER_UP_GAP_METERS) }
    }

    private fun spanMeters(
        first: LocationUpdateSample,
        last: LocationUpdateSample,
    ): Double =
        TurfMeasurement.distance(
            Point.fromLngLat(first.longitude, first.latitude),
            Point.fromLngLat(last.longitude, last.latitude),
            UNIT_METRES,
        )
}
