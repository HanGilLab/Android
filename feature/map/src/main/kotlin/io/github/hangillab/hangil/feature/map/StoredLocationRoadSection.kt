package io.github.hangillab.hangil.feature.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString

internal object StoredLocationRoadSection {
    const val SOURCE = "hangil-stored-location-road-section"
    const val LAYER = "hangil-stored-location-road-section-line"

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
