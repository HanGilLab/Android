package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CourseRoadSection
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionReviewSampleJsonTest {
    private val value =
        SessionReviewSample(
            LocationRecordingEvent(
                Long.MAX_VALUE,
                "source",
                "run",
                1024,
                Long.MAX_VALUE,
                LocationUpdateReading.Sample(
                    LocationUpdateSample(
                        -0.0,
                        127.12345678901234,
                        java.lang.Double.MIN_VALUE,
                        -0.0f,
                        java.lang.Float.MIN_VALUE,
                        Long.MIN_VALUE,
                        Long.MAX_VALUE,
                    ),
                ),
            ),
            "process",
            Long.MAX_VALUE,
            Long.MAX_VALUE - 1,
        )

    @Test fun `all raw fields and full event owner survive canonical encoding without interpreting SDK units`() {
        val raw = checkNotNull(SessionReviewSampleJson.encode(value))
        assertTrue(raw.startsWith("{\"format\":1,"))
        assertEquals(value, SessionReviewSampleJson.decode(raw))
        assertEquals(raw, SessionReviewSampleJson.encode(SessionReviewSampleJson.decode(raw)))
        assertNull(SessionReviewSampleJson.decode(null))
        assertNull(SessionReviewSampleJson.encode(null))
    }

    @Test fun `section links preserve the exact callback and reject noncanonical section fields`() {
        val linked =
            SessionReviewSample(
                value.event,
                value.processId,
                value.startedAtEpochMillis,
                value.startedAtElapsedRealtimeMillis,
                CourseRoadSection(1, 3),
            )
        val raw = checkNotNull(SessionReviewSampleJson.encode(linked))
        assertTrue(raw.startsWith("{\"format\":2,"))
        val decoded = checkNotNull(SessionReviewSampleJson.decode(raw))
        assertEquals(linked, decoded)
        assertTrue(value.sameCallback(decoded))
        val invalid =
            listOf(
                raw.replace("\"firstNodeIndex\":1", "\"firstNodeIndex\":-1"),
                raw.replace("\"lastNodeIndex\":3", "\"lastNodeIndex\":4294967299"),
                raw.replace("\"lastNodeIndex\":3", "\"lastNodeIndex\":1"),
                JSONObject(raw).apply { remove("section") }.toString(),
            )
        invalid.forEach { assertThrows(IllegalStateException::class.java) { SessionReviewSampleJson.decode(it) } }
    }

    @Test fun `partial coerced duplicate extra and malformed anchors are errors rather than absence`() {
        val raw = checkNotNull(SessionReviewSampleJson.encode(value))
        val invalid =
            listOf(
                "",
                "null",
                "{}",
                raw + " ",
                "{\"format\":1," + raw.substring(1),
                JSONObject(raw).put("extra", true).toString(),
                JSONObject(raw).put("format", "1").toString(),
                JSONObject(raw).apply { remove("processId") }.toString(),
                JSONObject(raw).apply { getJSONObject("event").put("id", Long.MAX_VALUE.toString()) }.toString(),
                JSONObject(raw).apply { getJSONObject("event").put("ordinal", 1025) }.toString(),
                JSONObject(raw).apply { getJSONObject("event").getJSONObject("sample").put("source", "DEMO") }.toString(),
                JSONObject(raw).apply { getJSONObject("event").getJSONObject("sample").put("latitude", 0) }.toString(),
                JSONObject(raw).apply { getJSONObject("event").getJSONObject("sample").put("headingRaw", "NaN") }.toString(),
                JSONObject(raw).apply { getJSONObject("event").getJSONObject("sample").remove("confidenceRaw") }.toString(),
            )
        invalid.forEach { assertThrows(IllegalStateException::class.java) { SessionReviewSampleJson.decode(it) } }
    }
}
