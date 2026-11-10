package io.github.hangillab.hangil.feature.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

internal object StoredLocationRoadProjection {
    const val SOURCE = "hangil-course-road-nearest-source"
    const val LAYER = "hangil-course-road-nearest"

    fun add(
        style: Style,
        nearest: Point,
        roadColor: Int,
        borderColor: Int,
        fontScale: Float,
    ) {
        style.addSource(GeoJsonSource(SOURCE, Feature.fromGeometry(nearest)))
        style.addLayer(
            CircleLayer(LAYER, SOURCE).withProperties(
                circleRadius(8f * fontScale),
                circleColor(roadColor),
                circleStrokeColor(borderColor),
                circleStrokeWidth(3f),
            ),
        )
    }
}
