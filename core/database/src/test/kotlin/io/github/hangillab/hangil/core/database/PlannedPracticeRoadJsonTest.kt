package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlannedPracticeRoadJsonTest {
    private val target =
        PlannedPracticeRoad(
            "source",
            "version",
            CourseRoadSnapshot(
                "road",
                "Quoted \"road\"",
                "residential",
                Long.MAX_VALUE,
                Int.MAX_VALUE,
                Long.MAX_VALUE,
                listOf(
                    CourseRoadSnapshot.Node(Long.MAX_VALUE, Int.MAX_VALUE, -0.0, 127.12345678901234),
                    CourseRoadSnapshot.Node(2, 3, java.lang.Double.MIN_VALUE, 127.12345678901236),
                ),
            ),
        )

    @Test fun `canonical snapshot preserves every integer bit coordinate precision signed zero and node order`() {
        val encoded = checkNotNull(PlannedPracticeRoadJson.encode(target))
        assertEquals(target, PlannedPracticeRoadJson.decode(encoded))
        assertEquals(encoded, PlannedPracticeRoadJson.encode(PlannedPracticeRoadJson.decode(encoded)))
        assertNull(PlannedPracticeRoadJson.decode(null))
        assertNull(PlannedPracticeRoadJson.encode(null))
    }

    @Test fun `malformed noncanonical duplicate unknown partial and coerced fields never become absence`() {
        val encoded = checkNotNull(PlannedPracticeRoadJson.encode(target))
        val invalid =
            listOf(
                "",
                "null",
                "{}",
                encoded + " ",
                "{\"format\":1," + encoded.substring(1),
                JSONObject(encoded).put("unknown", true).toString(),
                JSONObject(encoded).put("format", "1").toString(),
                JSONObject(encoded).put("origin", "SDK_UPDATE").toString(),
                JSONObject(encoded).apply { getJSONObject("road").put("wayId", Long.MAX_VALUE.toString()) }.toString(),
                JSONObject(encoded).apply { getJSONObject("road").remove("title") }.toString(),
                JSONObject(encoded).apply { getJSONObject("road").getJSONArray("nodes").getJSONObject(0).put("latitude", 0) }.toString(),
                JSONObject(
                    encoded,
                ).apply { getJSONObject("road").getJSONArray("nodes").getJSONObject(0).put("latitude", "NaN") }.toString(),
                JSONObject(
                    encoded,
                ).apply { getJSONObject("road").getJSONArray("nodes").getJSONObject(0).put("version", 4_294_967_297L) }.toString(),
            )
        invalid.forEach { raw -> assertThrows(IllegalStateException::class.java) { PlannedPracticeRoadJson.decode(raw) } }
    }
}
