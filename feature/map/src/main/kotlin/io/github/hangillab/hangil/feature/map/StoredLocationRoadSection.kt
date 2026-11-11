package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSection
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

internal object StoredLocationRoadSection {
    const val SOURCE = "hangil-stored-location-road-section"
    const val LAYER = "hangil-stored-location-road-section-line"
    const val PUBLIC_SOURCE = "hangil-planned-road-section"
    const val PUBLIC_LAYER = "hangil-planned-road-section-line"

    fun publicLine(
        road: CourseRoadSnapshot,
        section: CourseRoadSection,
    ): LineString? =
        section.takeIf { it.isValidFor(road) }?.let {
            LineString.fromLngLats(it.nodesOf(road).map { node -> Point.fromLngLat(node.longitude, node.latitude) })
        }

    fun addPublic(
        style: Style,
        section: LineString,
        color: Int,
        candidate: Boolean,
    ) {
        style.addSource(GeoJsonSource(PUBLIC_SOURCE, Feature.fromGeometry(section)))
        val line =
            LineLayer(PUBLIC_LAYER, PUBLIC_SOURCE).withProperties(
                lineColor(color),
                lineWidth(7f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        if (candidate) line.setProperties(lineDasharray(arrayOf(2f, 2f)))
        style.addLayer(line)
    }

    fun add(
        style: Style,
        section: LineString,
        color: Int,
    ) {
        style.addSource(GeoJsonSource(SOURCE, Feature.fromGeometry(section)))
        style.addLayer(
            LineLayer(LAYER, SOURCE).withProperties(
                lineColor(color),
                lineWidth(12f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
}
