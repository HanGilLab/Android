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
        val west = minOf(sample.longitude, road.nodes.minOf { it.longitude })
        val east = maxOf(sample.longitude, road.nodes.maxOf { it.longitude })
        if (east - west >= 180.0) return null
        val point = Point.fromLngLat(sample.longitude, sample.latitude)
        val line = road.nodes.map { Point.fromLngLat(it.longitude, it.latitude) }
        return try {
            val nearest = TurfMisc.nearestPointOnLine(point, line, UNIT_METRES).geometry() as? Point ?: return null
            TurfMeasurement.distance(point, nearest, UNIT_METRES).takeIf { it.isFinite() && it >= 0 }
        } catch (_: RuntimeException) {
            null
        }
    }
}
