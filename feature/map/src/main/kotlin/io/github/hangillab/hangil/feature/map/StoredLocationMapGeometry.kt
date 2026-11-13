package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString

/** Geometry for one immutable map input. MapLibre view, style, and camera updates remain on the UI thread. */
internal class StoredLocationMapGeometry(
    val points: FeatureCollection,
    val trace: FeatureCollection?,
    val center: LatLng,
    val bounds: LatLngBounds?,
    val section: StoredLocationRoadSuggestion.Suggestion?,
    val publicSection: LineString?,
    val reviewDistance: Double?,
    val suggestedRoadId: String?,
) {
    companion object {
        fun prepare(
            input: StoredLocationMapInput,
            road: CourseRoadSnapshot?,
            reviewRoad: SessionReviewRoad?,
            preview: Boolean,
            renderGeneration: Long? = null,
        ): StoredLocationMapGeometry {
            val selectedSample = (input.selectedEvent?.reading as? LocationUpdateReading.Sample)?.sample
            val shownSamples = if (preview) listOf(checkNotNull(input.selectedEvent)) else input.samples
            val coordinates =
                buildList {
                    for (event in shownSamples) {
                        val sample = (event.reading as LocationUpdateReading.Sample).sample
                        add(LatLng(sample.latitude, sample.longitude))
                    }
                    for (node in road?.nodes.orEmpty()) add(LatLng(node.latitude, node.longitude))
                }
            val center = coordinates.first()
            val bounds =
                if (coordinates.all { it.latitude == center.latitude && it.longitude == center.longitude }) {
                    null
                } else {
                    LatLngBounds.Builder().includes(coordinates).build()
                }
            val section = reviewRoad?.let { StoredLocationRoadSuggestion.section(input, it) }
            val publicSection =
                reviewRoad?.let {
                    (it.sampleAnchor?.roadSection ?: section?.publicRange)?.let { range ->
                        StoredLocationRoadSection.publicLine(it.road, range)
                    }
                }
            val reviewDistance =
                if (selectedSample != null && reviewRoad != null) {
                    StoredLocationRoadDistance.meters(selectedSample, reviewRoad.road)
                } else {
                    null
                }
            return StoredLocationMapGeometry(
                points = StoredLocationPoint.features(input, selectedOnly = preview, renderGeneration = renderGeneration),
                trace = if (preview) null else input.trace,
                center = center,
                bounds = bounds,
                section = section,
                publicSection = publicSection,
                reviewDistance = reviewDistance,
                suggestedRoadId = if (reviewRoad == null) StoredLocationRoadSuggestion.find(input)?.id else null,
            )
        }
    }
}
