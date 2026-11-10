package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfConstants.UNIT_METRES
import org.maplibre.turf.TurfMeasurement
import org.maplibre.turf.TurfMisc

/** Compares the saved coordinate with a public road shape using geometry only. */
internal object StoredLocationRoadDistance {
    fun meters(
        sample: LocationUpdateSample,
        road: CourseRoadSnapshot,
    ): Double? {
        val point = Point.fromLngLat(sample.longitude, sample.latitude)
        val nearest = nearestPoint(point, road) ?: return null
        return try {
            TurfMeasurement.distance(point, nearest, UNIT_METRES).takeIf { it.isFinite() && it >= 0 }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun nearestPoint(
        sample: LocationUpdateSample,
        road: CourseRoadSnapshot,
    ): Point? = nearestPoint(Point.fromLngLat(sample.longitude, sample.latitude), road)

    private fun nearestPoint(
        point: Point,
        road: CourseRoadSnapshot,
    ): Point? {
        val west = minOf(point.longitude(), road.nodes.minOf { it.longitude })
        val east = maxOf(point.longitude(), road.nodes.maxOf { it.longitude })
        if (east - west >= 180.0) return null
        val line = road.nodes.map { Point.fromLngLat(it.longitude, it.latitude) }
        return try {
            (TurfMisc.nearestPointOnLine(point, line, UNIT_METRES).geometry() as? Point)
                ?.takeIf { it.longitude().isFinite() && it.latitude().isFinite() }
        } catch (_: RuntimeException) {
            null
        }
    }
}
