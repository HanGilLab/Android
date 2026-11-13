package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs

/** Shows receipt order between adjacent saved coordinates. It does not estimate the traveled path. */
internal object StoredLocationTrace {
    const val SOURCE = "hangil-stored-location-trace"
    const val LAYER = "hangil-stored-location-trace-line"

    fun features(input: StoredLocationMapInput): FeatureCollection {
        require(StoredLocationPoint.canProject(input)) { "A stored sample is outside the map projection" }
        val links =
            buildList {
                for (index in 1 until input.events.size) {
                    val before = input.events[index - 1]
                    val after = input.events[index]
                    val start = (before.reading as? LocationUpdateReading.Sample)?.sample ?: continue
                    val end = (after.reading as? LocationUpdateReading.Sample)?.sample ?: continue
                    if (!canLink(input.run, start, end)) continue
                    val line =
                        LineString.fromLngLats(
                            listOf(Point.fromLngLat(start.longitude, start.latitude), Point.fromLngLat(end.longitude, end.latitude)),
                        )
                    add(
                        Feature.fromGeometry(line).apply {
                            addNumberProperty("from_ordinal", before.ordinal)
                            addNumberProperty("to_ordinal", after.ordinal)
                        },
                    )
                }
            }
        return FeatureCollection.fromFeatures(links)
    }

    fun hasLinks(input: StoredLocationMapInput): Boolean =
        (1 until input.events.size).any { index ->
            val start = (input.events[index - 1].reading as? LocationUpdateReading.Sample)?.sample ?: return@any false
            val end = (input.events[index].reading as? LocationUpdateReading.Sample)?.sample ?: return@any false
            canLink(input.run, start, end)
        }

    fun canLink(
        run: LocationRecordingRun,
        start: LocationUpdateSample,
        end: LocationUpdateSample,
    ): Boolean = isContinuous(run, start, end) && (start.latitude != end.latitude || start.longitude != end.longitude)

    fun isContinuous(
        run: LocationRecordingRun,
        start: LocationUpdateSample,
        end: LocationUpdateSample,
    ): Boolean {
        val startTime = start.receivedAtElapsedRealtimeMillis
        val endTime = end.receivedAtElapsedRealtimeMillis
        return startTime >= run.startedAtElapsedRealtimeMillis &&
            (run.endedAtElapsedRealtimeMillis?.let { endTime <= it } != false) &&
            endTime - startTime in 1 until LocationUpdateGateway.NO_UPDATE_TIMEOUT_MILLIS &&
            abs(start.longitude - end.longitude) < 180
    }

    fun add(
        style: Style,
        trace: FeatureCollection,
        color: Int,
    ) {
        if (trace.features().isNullOrEmpty()) return
        style.addSource(GeoJsonSource(SOURCE, trace))
        style.addLayer(LineLayer(LAYER, SOURCE).withProperties(lineColor(color), lineWidth(4f), lineDasharray(arrayOf(1f, 2f))))
    }
}
