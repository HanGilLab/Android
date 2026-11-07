package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class StoredLocationPointTest {
    @Test fun `one stored sample produces only one point in longitude latitude order`() {
        val sample = sample(37.5, 127.1)
        val features = checkNotNull(StoredLocationPoint.features(sample).features())
        assertEquals(1, features.size)
        val feature = features.single()
        assertTrue(feature.geometry() is Point)
        val point = feature.geometry() as Point
        assertEquals(sample.longitude, point.longitude(), 0.0)
        assertEquals(sample.latitude, point.latitude(), 0.0)
        assertEquals("SDK_UPDATE", feature.getStringProperty("source"))
        assertEquals(2, point.coordinates().size)
    }

    @Test fun `zero and negative coordinates remain actual coordinates`() {
        for ((latitude, longitude) in listOf(0.0 to 0.0, -35.0 to -75.0)) {
            val feature = checkNotNull(StoredLocationPoint.features(sample(latitude, longitude)).features()).single()
            val point = feature.geometry() as Point
            assertEquals(latitude, point.latitude(), 0.0)
            assertEquals(longitude, point.longitude(), 0.0)
        }
    }

    @Test fun `valid polar SDK coordinates are explicitly unavailable instead of clamped`() {
        assertTrue(StoredLocationPoint.canProject(sample(85.0, 0.0)))
        assertFalse(StoredLocationPoint.canProject(sample(90.0, 0.0)))
        assertFalse(StoredLocationPoint.canProject(sample(-90.0, 0.0)))
    }

    private fun sample(
        latitude: Double,
        longitude: Double,
    ) = LocationUpdateSample(latitude, longitude, 0.0, 0f, 0f, 1L, 1L)
}
