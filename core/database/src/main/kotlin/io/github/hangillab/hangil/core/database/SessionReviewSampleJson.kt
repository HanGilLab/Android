package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import org.json.JSONObject

/** Fixed field order and decimal strings preserve all stored raw numeric values, including signed zero. */
internal object SessionReviewSampleJson {
    fun encode(value: SessionReviewSample?): String? {
        if (value == null) return null
        val event = value.event
        val sample = value.sample
        return objectValue(
            "format" to if (value.roadSection == null) "1" else "2",
            "origin" to JSONObject.quote(value.origin),
            "processId" to JSONObject.quote(value.processId),
            "startedAtEpochMillis" to value.startedAtEpochMillis.toString(),
            "startedAtElapsedRealtimeMillis" to value.startedAtElapsedRealtimeMillis.toString(),
            "event" to
                objectValue(
                    "id" to event.id.toString(),
                    "sessionId" to JSONObject.quote(event.sessionId),
                    "runId" to JSONObject.quote(event.runId),
                    "ordinal" to event.ordinal.toString(),
                    "observedAtEpochMillis" to event.observedAtEpochMillis.toString(),
                    "sample" to
                        objectValue(
                            "source" to JSONObject.quote(sample.source.name),
                            "latitude" to JSONObject.quote(sample.latitude.toString()),
                            "longitude" to JSONObject.quote(sample.longitude.toString()),
                            "altitudeRaw" to JSONObject.quote(sample.altitudeRaw.toString()),
                            "headingRaw" to JSONObject.quote(sample.headingRaw.toString()),
                            "confidenceRaw" to JSONObject.quote(sample.confidenceRaw.toString()),
                            "sdkTimestampMillis" to sample.sdkTimestampMillis.toString(),
                            "receivedAtElapsedRealtimeMillis" to sample.receivedAtElapsedRealtimeMillis.toString(),
                        ),
                ),
            *CourseRoadSectionJson.fields(value.roadSection),
        )
    }

    fun decode(raw: String?): SessionReviewSample? {
        if (raw == null) return null
        try {
            val root = JSONObject(raw)
            check(text(root, "origin") == "USER_LINKED_STORED_SAMPLE")
            val section = CourseRoadSectionJson.decode(root, integer(root, "format"))
            val event = root.get("event") as? JSONObject ?: error("Invalid linked event object")
            val sample = event.get("sample") as? JSONObject ?: error("Invalid linked sample object")
            check(text(sample, "source") == "SDK_UPDATE")
            val ordinal = integer(event, "ordinal")
            check(ordinal in 1..Int.MAX_VALUE.toLong())
            val value =
                SessionReviewSample(
                    LocationRecordingEvent(
                        integer(event, "id"),
                        text(event, "sessionId"),
                        text(event, "runId"),
                        ordinal.toInt(),
                        integer(event, "observedAtEpochMillis"),
                        LocationUpdateReading.Sample(
                            LocationUpdateSample(
                                text(sample, "latitude").toDouble(),
                                text(sample, "longitude").toDouble(),
                                text(sample, "altitudeRaw").toDouble(),
                                text(sample, "headingRaw").toFloat(),
                                text(sample, "confidenceRaw").toFloat(),
                                integer(sample, "sdkTimestampMillis"),
                                integer(sample, "receivedAtElapsedRealtimeMillis"),
                            ),
                        ),
                    ),
                    text(root, "processId"),
                    integer(root, "startedAtEpochMillis"),
                    integer(root, "startedAtElapsedRealtimeMillis"),
                    section,
                )
            check(encode(value) == raw)
            return value
        } catch (_: Exception) {
            throw IllegalStateException("Invalid stored linked review sample")
        }
    }

    private fun objectValue(vararg fields: Pair<String, String>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> JSONObject.quote(key) + ":" + value }

    private fun text(
        value: JSONObject,
        key: String,
    ): String = value.get(key) as? String ?: error("Invalid linked sample text")

    private fun integer(
        value: JSONObject,
        key: String,
    ): Long =
        when (val raw = value.get(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> error("Invalid linked sample integer")
        }
}
