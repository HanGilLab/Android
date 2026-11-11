package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfConstants.UNIT_METRES
import org.maplibre.turf.TurfMeasurement
import org.maplibre.turf.TurfMisc
import kotlin.math.abs

/** Suggests a nearby registered road for review, without inferring a driven path. */
internal object StoredLocationRoadSuggestion {
    private const val NEIGHBORS_PER_SIDE = 2
    private const val MIN_SAMPLES = 3
    private const val MIN_SPAN_METERS = 20.0
    private const val MAX_ROAD_DISTANCE_METERS = 25.0
    private const val MIN_RUNNER_UP_GAP_METERS = 15.0

    fun find(input: StoredLocationMapInput): CourseRoadSnapshot? = input.selectedEvent?.let { match(input, it)?.road }

    fun section(
        input: StoredLocationMapInput,
        review: SessionReviewRoad,
    ): LineString? {
        val event = input.selectedEvent ?: return null
        if (event.reading !is LocationUpdateReading.Sample || SessionReviewSample.from(input.run, event) != review.sampleAnchor) return null
        val match = match(input, event)?.takeIf { it.road == review.road } ?: return null
        return try {
            projectedSection(match)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun match(
        input: StoredLocationMapInput,
        selectedEvent: LocationRecordingEvent,
    ): Match? {
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
        return if (samples.drop(1).all { nearestRoad(it, input.courseRoads) == nearest }) Match(nearest, samples) else null
    }

    private fun projectedSection(match: Match): LineString? {
        val line = match.road.nodes.map { Point.fromLngLat(it.longitude, it.latitude) }
        val segments = line.zipWithNext()
        val offsets = segments.map { (start, end) -> distance(start, end) }.runningFold(0.0, Double::plus)
        if (offsets.any { !it.isFinite() }) return null
        val samples = match.samples.map { Point.fromLngLat(it.longitude, it.latitude) }
        val projections =
            samples.map { sample ->
                val candidates =
                    segments.mapIndexedNotNull { index, (start, end) ->
                        val length = offsets[index + 1] - offsets[index]
                        if (length == 0.0) return@mapIndexedNotNull null
                        val point = TurfMisc.nearestPointOnLine(sample, listOf(start, end), UNIT_METRES).geometry() as? Point ?: return null
                        val meters = distance(sample, point)
                        val offset = offsets[index] + distance(start, point).coerceAtMost(length)
                        if (!meters.isFinite() || !offset.isFinite()) return null
                        Projection(index, point, meters, offset)
                    }
                // A clamped endpoint is a minimum only when its other segment also projects to that vertex.
                val minima =
                    candidates.filterIndexed { index, projection ->
                        val continuesBefore =
                            projection.point == line[projection.index] &&
                                index > 0 && candidates[index - 1].point != projection.point
                        val continuesAfter =
                            projection.point == line[projection.index + 1] &&
                                index < candidates.lastIndex && candidates[index + 1].point != projection.point
                        !continuesBefore && !continuesAfter
                    }
                val nearest = minima.minByOrNull { it.meters } ?: return null
                if (nearest.meters > MAX_ROAD_DISTANCE_METERS ||
                    minima.any {
                        it.meters <= MAX_ROAD_DISTANCE_METERS &&
                            it.meters - nearest.meters < MIN_RUNNER_UP_GAP_METERS &&
                            abs(it.offset - nearest.offset) >= MIN_SPAN_METERS
                    }
                ) {
                    return null
                }
                nearest
            }
        val ordered = if (projections.first().offset <= projections.last().offset) projections else projections.asReversed()
        if (ordered.zipWithNext().any { (start, end) -> end.offset <= start.offset }) return null
        val first = ordered.first()
        val last = ordered.last()
        val span = last.offset - first.offset
        val sampleLength = samples.zipWithNext().sumOf { (start, end) -> distance(start, end) }
        if (!sampleLength.isFinite() || span !in MIN_SPAN_METERS..(sampleLength + 2 * MAX_ROAD_DISTANCE_METERS)) return null

        val section = listOf(first.point) + line.subList(first.index + 1, last.index + 1) + last.point
        val points = section.filterIndexed { index, point -> index == 0 || point != section[index - 1] }
        return points.takeIf { it.size >= 2 && it.distinct().size == it.size }?.let(LineString::fromLngLats)
    }

    private fun distance(
        start: Point,
        end: Point,
    ): Double = TurfMeasurement.distance(start, end, UNIT_METRES)

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
        distance(
            Point.fromLngLat(first.longitude, first.latitude),
            Point.fromLngLat(last.longitude, last.latitude),
        )

    private data class Match(
        val road: CourseRoadSnapshot,
        val samples: List<LocationUpdateSample>,
    )

    private data class Projection(
        val index: Int,
        val point: Point,
        val meters: Double,
        val offset: Double,
    )
}
