package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs

/** Draws the original saved coordinates and the selection ring without snapping or interpolation. */
internal object StoredLocationPoint {
    const val SOURCE = "hangil-stored-location-point"
    const val LAYER = "hangil-stored-location-circle"
    const val SELECTION = "hangil-stored-location-selection"
    private const val MERCATOR_LATITUDE_LIMIT = 85.0511287798066

    fun canProject(input: StoredLocationMapInput): Boolean =
        input.samples.all { abs((it.reading as LocationUpdateReading.Sample).sample.latitude) <= MERCATOR_LATITUDE_LIMIT }

    fun features(input: StoredLocationMapInput): FeatureCollection {
        require(canProject(input)) { "A stored sample is outside the map projection" }
        return FeatureCollection.fromFeatures(
            input.samples.map { event ->
                val sample = (event.reading as LocationUpdateReading.Sample).sample
                Feature.fromGeometry(Point.fromLngLat(sample.longitude, sample.latitude)).apply {
                    addStringProperty("source", sample.source.name)
                    addStringProperty("event_id", event.id.toString())
                    addStringProperty("run_id", event.runId)
                    addNumberProperty("ordinal", event.ordinal)
                }
            },
        )
    }

    fun selectionFilter(eventId: String?): Expression =
        if (eventId == null) Expression.literal(false) else Expression.eq(get("event_id"), Expression.literal(eventId))

    fun add(
        style: Style,
        input: StoredLocationMapInput,
        fill: Int,
        border: Int,
        fontScale: Float,
    ) {
        style.addSource(GeoJsonSource(SOURCE, features(input)))
        style.addLayer(
            CircleLayer(LAYER, SOURCE).withProperties(
                circleRadius(14f * fontScale),
                circleColor(fill),
                circleStrokeColor(border),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            CircleLayer(SELECTION, SOURCE)
                .withProperties(
                    circleRadius(22f * fontScale),
                    circleOpacity(0f),
                    circleStrokeColor(fill),
                    circleStrokeWidth(4f),
                ).withFilter(selectionFilter(input.selectedSampleId)),
        )
    }
}
