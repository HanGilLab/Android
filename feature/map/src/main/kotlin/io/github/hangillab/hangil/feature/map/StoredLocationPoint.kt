package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs

/** A single stored callback coordinate, with no interpolation or inferred road geometry. */
internal object StoredLocationPoint {
    const val SOURCE = "hangil-stored-location-point"
    const val LAYER = "hangil-stored-location-circle"
    private const val MERCATOR_LATITUDE_LIMIT = 85.0511287798066

    fun canProject(sample: LocationUpdateSample): Boolean = abs(sample.latitude) <= MERCATOR_LATITUDE_LIMIT

    fun features(sample: LocationUpdateSample): FeatureCollection {
        require(canProject(sample)) { "Stored sample is outside the map projection" }
        return FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(sample.longitude, sample.latitude)).apply {
                addStringProperty("source", sample.source.name)
            },
        )
    }

    fun add(
        style: Style,
        sample: LocationUpdateSample,
        fill: Int,
        border: Int,
        fontScale: Float,
    ) {
        style.addSource(GeoJsonSource(SOURCE, features(sample)))
        style.addLayer(
            CircleLayer(LAYER, SOURCE).withProperties(
                circleRadius(18f * fontScale),
                circleColor(fill),
                circleStrokeColor(border),
                circleStrokeWidth(4f),
            ),
        )
    }
}
