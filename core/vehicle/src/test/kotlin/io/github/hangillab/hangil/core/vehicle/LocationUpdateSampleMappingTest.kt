package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.model.LocationData
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationUpdateSampleMappingTest {
    @Test fun `update callback retains raw values and a separate app receipt without claiming freshness`() {
        val reading = LocationData(12.345, 23.456, -8.0, -2f, -4.5f, -17L).toLocationUpdateReading(42)
        val sample = (reading as LocationUpdateReading.Sample).sample
        assertEquals(LocationUpdateSource.SDK_UPDATE, sample.source)
        assertEquals(-17L, sample.sdkTimestampMillis)
        assertEquals(42L, reading.receivedAtElapsedRealtimeMillis)
        assertEquals(-8.0, sample.altitudeRaw, 0.0)
        assertEquals(-2f, sample.headingRaw)
        assertEquals(-4.5f, sample.confidenceRaw)
        assertFalse(reading.toString().contains("12.345"))
        assertFalse(reading.toString().contains("23.456"))
    }

    @Test fun `only a real null callback maps to Empty while zero coordinates remain a sample`() {
        assertEquals(LocationUpdateReading.Empty(3), null.toLocationUpdateReading(3))
        val sample = LocationData(0.0, 0.0, 0.0, 0f, 0f, 0L).toLocationUpdateReading(3)
        assertEquals(0.0, (sample as LocationUpdateReading.Sample).sample.latitude, 0.0)
    }

    @Test fun `invalid callback structure throws rather than becoming empty or a repaired coordinate`() {
        listOf(
            LocationData(Double.NaN, 0.0, 0.0, 0f, 0f, 0L),
            LocationData(0.0, Double.POSITIVE_INFINITY, 0.0, 0f, 0f, 0L),
            LocationData(90.01, 0.0, 0.0, 0f, 0f, 0L),
            LocationData(0.0, -180.01, 0.0, 0f, 0f, 0L),
            LocationData(0.0, 0.0, Double.NaN, 0f, 0f, 0L),
            LocationData(0.0, 0.0, 0.0, Float.NaN, 0f, 0L),
            LocationData(0.0, 0.0, 0.0, 0f, Float.NEGATIVE_INFINITY, 0L),
        ).forEach { value -> assertThrows(IllegalArgumentException::class.java) { value.toLocationUpdateReading(3) } }
        assertThrows(IllegalArgumentException::class.java) { null.toLocationUpdateReading(-1) }
    }
}
