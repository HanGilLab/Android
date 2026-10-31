package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.hangillab.hangil.feature.map.R as MapR

/**
 * Real MapLibre13.5/OpenFreeMap UI opening, with the same baseline/comparison method and path.
 * Before the Activity rule launches, the external runner must preserve original SQLite/settings,
 * confirm no active session and own the real SDK P/UX and fresh-process/debug APK provenance.
 * Click, map pre-scroll, renderer/network, native style/source/fit-camera and Compose checks
 * are included. No GPS/current location is requested; fixed OSM previews remain illustrations.
 * It is not an app-only frame/GPU/SDK timer or a user-speed claim. No capture, setting change,
 * grant or domain write occurs. Frozen raw inputs, environment and samples remain private.
 */
@RunWith(AndroidJUnit4::class)
class MapOpeningMeasureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun measureMapOpeningAndPreserveOriginalData() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "실제 지도 진입 계측과 외부 원본 보존을 준비한 실행에서만 측정합니다",
            arguments.getString("manualMapOpeningMeasure") == "true",
        )
        val stage = checkNotNull(arguments.getString("measurementStage")) { "기준 또는 비교 단계를 명시해야 합니다" }
        assertTrue("정의한 기준/비교 단계만 실행해야 합니다", stage == "baseline" || stage == "comparison")
        val productCommit = checkNotNull(arguments.getString("declaredProductCommit")) { "제품 원본 선언이 필요합니다" }
        val harnessRevision = checkNotNull(arguments.getString("declaredHarnessRevision")) { "계측 원본 선언이 필요합니다" }
        assertTrue("제품 원본은 완전한 SHA여야 합니다", productCommit.matches(Regex("[0-9a-fA-F]{40}")))
        assertTrue("계측 원본은 완전한 SHA여야 합니다", harnessRevision.matches(Regex("[0-9a-fA-F]{40}")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("전용 debug 앱에서만 실제 UI를 측정합니다", context.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("동일한 API34 환경에서 측정해야 합니다", Build.VERSION.SDK_INT == 34)
        assertTrue("debug 빌드 조건을 유지해야 합니다", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val configuration = context.resources.configuration
        assertTrue("기준과 비교는 글꼴1.0이어야 합니다", kotlin.math.abs(configuration.fontScale - 1f) < 0.001f)
        assertTrue(
            "기준과 비교는 라이트여야 합니다",
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO,
        )
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val baselineFile = File(context.cacheDir, "measurements/map-point-tap-entry-baseline.json")
        val baselineHash = if (stage == "comparison") completedBaselineHash(baselineFile) else null
        val baseline = if (stage == "comparison") readBaseline(baselineFile).also(::validateBaseline) else null
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 계측을 시작하지 않습니다", before.sessions.none { it.isActive })
            val selection = checkNotNull(before.preparation) { "원래 준비가 있어야 동일 입력을 확인할 수 있습니다" }
            val prepared =
                checkNotNull((graph.catalog().resolve(selection) as? Preparation.Ready)?.practice) {
                    "원래 준비를 현재 카탈로그에서 확인해야 합니다"
                }
            val preview = checkNotNull(prepared.course.mapPreview) { "선택한 코스의 고정 preview가 필요합니다" }
            assertTrue("같은 세 고정 지점을 실제로 확인해야 합니다", preview.points.size == 3)
            val source = previewSourceJson(selection, prepared, preview)
            assertTrue(
                "기존 세션 ID는 서로 달라야 합니다",
                before.sessions
                    .map { it.sessionId }
                    .distinct()
                    .size == before.sessions.size,
            )
            val originalData = dataJson(before)
            val frozenCatalog = catalogJson(graph)
            val environment = environmentJson()
            val contract = measurementContract()
            if (baseline != null) {
                requireSameJson(baseline.getJSONObject("originalData"), originalData, "originalData")
                requireSameJson(baseline.getJSONObject("fixedSource"), source, "fixedSource")
                requireSameJson(baseline.getJSONObject("catalog"), frozenCatalog, "catalog")
                requireSameJson(baseline.getJSONObject("environment"), environment, "environment")
                requireSameJson(baseline.getJSONObject("measurementContract"), contract, "measurementContract")
            }
            val output = File(context.cacheDir, "measurements/map-point-tap-entry-$stage.json")
            reserve(output)
            val warmups = JSONArray()
            val samples = JSONArray()
            val report =
                JSONObject()
                    .put("schema", 1)
                    .put("kind", "map_point_tap_entry")
                    .put("measurementStage", stage)
                    .put("runNonce", UUID.randomUUID().toString())
                    .put("status", "started")
                    .put("declaredProductCommit", productCommit.lowercase(Locale.ROOT))
                    .put("declaredHarnessRevision", harnessRevision.lowercase(Locale.ROOT))
                    .put("revisionDeclarationSource", "external_runner_not_inferred_on_device")
                    .put("productApkSha256", sha256(File(context.applicationInfo.sourceDir)))
                    .put("instrumentationApkSha256", sha256(File(instrumentation.context.applicationInfo.sourceDir)))
                    .put("originalData", originalData)
                    .put("fixedSource", source)
                    .put("catalog", frozenCatalog)
                    .put("environment", environment)
                    .put("measurementContract", contract)
                    .put("plannedWarmups", WARMUPS)
                    .put("plannedSamples", SAMPLES)
                    .put("warmups", warmups)
                    .put("samples", samples)
                    .put("preservedOriginalData", false)
                    .put("returnedHome", false)
                    .put("excludedSamples", 0)
                    .put("causalPerformanceClaim", false)
            if (baseline != null) {
                report
                    .put("baselineSha256", baselineHash)
                    .put("baselineProductCommit", baseline.getString("declaredProductCommit"))
                    .put("baselineHarnessRevision", baseline.getString("declaredHarnessRevision"))
                    .put("baselineUnchanged", false)
            }
            write(output, report)
            val sdkObserver =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primaryFailure: Throwable? = null
            var cleanupFailure: Throwable? = null
            var measurementComplete = false
            try {
                awaitAllowed(graph)
                report.put("initialLiveGateAllowed", liveAllowed(graph))
                for (index in 0 until WARMUPS + SAMPLES) {
                    assertPreserved(graph, before)
                    requireSameJson(frozenCatalog, catalogJson(graph), "catalogBeforeCall")
                    requireSameJson(environment, environmentJson(), "environmentBeforeCall")
                    assertTrue("실제 P와 UX 허용에서만 이력을 열어야 합니다", liveAllowed(graph))
                    assertHomeSelection(prepared)
                    scrollVisible("home_map")
                    compose.waitForIdle()
                    requireForeground()
                    val viewport = checkNotNull(appViewport()) { "실제 앱 가용 영역이 필요합니다" }
                    assertTrue("클릭 직전에도 실제 P와 UX가 허용돼야 합니다", liveAllowed(graph))
                    val array = if (index < WARMUPS) warmups else samples
                    val sample =
                        JSONObject()
                            .put("index", if (index < WARMUPS) index + 1 else index - WARMUPS + 1)
                            .put("status", "started")
                            .put("elapsedNs", JSONObject.NULL)
                            .put("elapsedMs", JSONObject.NULL)
                            .put("endpointConfirmed", false)
                            .put("sourceMatched", false)
                            .put("returnedHome", false)
                    array.put(sample)
                    val started = SystemClock.elapsedRealtimeNanos()
                    try {
                        compose.onNodeWithTag("home_map", useUnmergedTree = true).performClick()
                        val native = awaitMapEndpoint(started, viewport, preview)
                        val elapsed = SystemClock.elapsedRealtimeNanos() - started
                        sample
                            .put("elapsedNs", elapsed)
                            .put("elapsedMs", elapsed / NANOS_PER_MS)
                            .put("endpointConfirmed", true)
                            .put("nativeReadback", nativeJson(native))
                        assertTrue("실제 지도 endpoint는 클릭 시작부터30초 안에 확인돼야 합니다", elapsed in 0..ENDPOINT_TIMEOUT_NS)
                        requireForeground()
                        requireSameJson(environment, environmentJson(), "environmentAfterCall")
                        assertTrue("표시 뒤에도 실제 P와 UX 허용을 유지해야 합니다", liveAllowed(graph))
                        assertMapSource(prepared, preview)
                        sample.put("sourceMatched", true)
                        assertPreserved(graph, before)
                        closeMap()
                        assertHomeSelection(prepared)
                        assertPreserved(graph, before)
                        sample.put("returnedHome", true).put("status", "success")
                    } catch (failure: Throwable) {
                        if (sample.isNull("elapsedNs")) {
                            val elapsed = SystemClock.elapsedRealtimeNanos() - started
                            sample.put("elapsedNs", elapsed).put("elapsedMs", elapsed / NANOS_PER_MS)
                        }
                        sample.put("status", "failed").put("failureClass", failure.javaClass.simpleName)
                        throw failure
                    }
                    if (index == WARMUPS - 1) {
                        report.put("status", "warmups_completed")
                        write(output, report)
                    }
                }
                assertTrue("워밍업을 모두 실제로 수행해야 합니다", successful(warmups) == WARMUPS)
                assertTrue("모든 원값을 실제로 수집해야 합니다", successful(samples) == SAMPLES)
                val measured = statistics(samples)
                report.put("statistics", measured)
                if (baseline != null) {
                    val previous = baseline.getJSONObject("statistics")
                    val beforeMedian = previous.getDouble("median")
                    val afterMedian = measured.getDouble("median")
                    report.put(
                        "comparison",
                        JSONObject()
                            .put("beforeStatistics", previous)
                            .put("afterStatistics", measured)
                            .put("medianChangeMs", afterMedian - beforeMedian)
                            .put(
                                "relativeMedianChangePercent",
                                if (beforeMedian == 0.0) {
                                    JSONObject.NULL
                                } else {
                                    (afterMedian - beforeMedian) / beforeMedian * 100.0
                                },
                            ).put("direction", "comparison minus baseline; positive is slower")
                            .put("causalPerformanceClaim", false)
                            .put("sequentialRunLimitation", "environment, cache, thermal and execution-order drift are not eliminated"),
                    )
                }
                measurementComplete = true
            } catch (failure: Throwable) {
                primaryFailure = failure
                report.put("failureClass", failure.javaClass.simpleName)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (nodes("map_close").size == 1) closeMap()
                        assertTrue("최종 화면은 원래 홈이어야 합니다", nodes("home_map").size == 1 && nodes("map_view").isEmpty())
                        assertHomeSelection(prepared)
                        report.put("returnedHome", true)
                        assertPreserved(graph, before)
                        report.put("preservedOriginalData", true)
                        requireSameJson(frozenCatalog, catalogJson(graph), "catalogAfterRun")
                        requireSameJson(environment, environmentJson(), "environmentAfterRun")
                        if (baselineHash != null) {
                            assertTrue("완료된 기준 파일은 변경하면 안 됩니다", sha256(baselineFile) == baselineHash)
                            report.put("baselineUnchanged", true)
                        }
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                        report.put("cleanupFailureClass", failure.javaClass.simpleName)
                        primaryFailure?.addSuppressed(failure)
                    } finally {
                        sdkObserver.cancelAndJoin()
                        report
                            .put("warmupCounts", counts(warmups, WARMUPS))
                            .put("sampleCounts", counts(samples, SAMPLES))
                            .put("excludedSamples", 0)
                            .put(
                                "status",
                                if (measurementComplete && primaryFailure == null && cleanupFailure == null) "completed" else "failed",
                            )
                        try {
                            write(output, report)
                        } catch (failure: Throwable) {
                            val primary = primaryFailure
                            val cleanup = cleanupFailure
                            if (primary != null) {
                                primary.addSuppressed(failure)
                            } else if (cleanup != null) {
                                cleanup.addSuppressed(failure)
                            } else {
                                throw failure
                            }
                        }
                    }
                }
                if (primaryFailure == null && cleanupFailure != null) throw checkNotNull(cleanupFailure)
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "MAP_OPENING_MEASUREMENT_SUCCESS stage=$stage warmups=5 samples=30" + System.lineSeparator())
                },
            )
        }
    }

    /** Same scroll/native endpoint in both versions; every query and blocking wait is included. */
    private suspend fun awaitMapEndpoint(
        started: Long,
        viewport: AppViewport,
        preview: CourseMapPreview,
    ): NativeReadback {
        fun remainingMs(): Long {
            val remaining = started + ENDPOINT_TIMEOUT_NS - SystemClock.elapsedRealtimeNanos()
            assertTrue("지도 endpoint의 실제30초 제한이 남아 있어야 합니다", remaining > 0)
            return (remaining + 999_999) / 1_000_000
        }
        compose.waitUntil(remainingMs()) { nodes("map_view").size == 1 }
        compose.onNodeWithTag("map_view", useUnmergedTree = true).performScrollTo()
        compose.waitUntil(remainingMs()) {
            nodes("map_ready").size == 1 && nodes("map_view").singleOrNull()?.let { fullyVisible(it, viewport) } == true
        }
        while (true) {
            val native = nativePreviewReadback(preview, minOf(5_000L, remainingMs()))
            if (native != null) {
                compose.waitForIdle()
                val rechecked = nativePreviewReadback(preview, minOf(5_000L, remainingMs()))
                if (rechecked != null && rechecked.view === native.view) {
                    assertTrue(
                        "idle 뒤 실제 지도 전체와 같은 Ready를 확인해야 합니다",
                        nodes("map_ready").size == 1 && nodes("map_view").singleOrNull()?.let { fullyVisible(it, viewport) } == true,
                    )
                    assertTrue(
                        "Compose/SDK blocking 제한과 별개로 실제30초를 넘기면 안 됩니다",
                        SystemClock.elapsedRealtimeNanos() - started <= ENDPOINT_TIMEOUT_NS,
                    )
                    return rechecked
                }
            }
            remainingMs()
            delay(100)
        }
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private fun liveAllowed(graph: RecordingTestEntryPoint): Boolean =
        allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)

    private fun requireForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "실제 앱 전면에서만 측정해야 합니다",
            instrumentation.uiAutomation.rootInActiveWindow
                ?.packageName
                ?.toString() == instrumentation.targetContext.packageName,
        )
    }

    private fun assertHomeSelection(prepared: PreparedPractice) {
        waitFor("home_map")
        assertTrue("지도를 닫은 홈이어야 합니다", nodes("map_view").isEmpty() && nodes("map_ready").isEmpty())
        assertText("saved_course", prepared.course.title)
        assertText("saved_task", prepared.task.title)
    }

    private fun assertMapSource(
        prepared: PreparedPractice,
        preview: CourseMapPreview,
    ) {
        assertText("map_course_title", prepared.course.title)
        assertText("map_preview_title", preview.title)
        assertText("map_demo_badge", text(MapR.string.map_demo_badge))
        preview.points.forEachIndexed { index, point -> assertText("map_point_$index", "${index + 1}. ${point.title}") }
        assertTrue("세 지점 밖의 placeholder를 만들면 안 됩니다", nodes("map_point_3").isEmpty())
        for (tag in listOf(
            "map_error",
            "map_retry",
            "map_locked",
            "map_loading",
            "map_preparation_empty",
            "map_preparation_outdated",
            "map_preparation_failed",
            "map_preparation_unavailable",
            "map_preparation_loading",
        )) {
            assertTrue("오류/잠금을 native 정상 endpoint로 바꾸면 안 됩니다", nodes(tag).isEmpty())
        }
    }

    private fun closeMap() {
        requireForeground()
        scrollVisible("map_close")
        compose.onNodeWithTag("map_close", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { nodes("home_map").size == 1 && nodes("map_view").isEmpty() }
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        waitFor(tag)
        assertTrue("원래 최신 source의 원문과 일치해야 합니다", nodeTexts(nodes(tag).single()).contains(expected))
    }

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)

    /** All JNI queries stay on Main and no MapLibreMap/Style/native handle escapes the callback. */
    private suspend fun nativePreviewReadback(
        preview: CourseMapPreview,
        timeoutMs: Long = 5_000,
    ): NativeReadback? {
        val result = CompletableDeferred<NativeReadback?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = currentMapViewOnMain()
            if (view == null || view.width <= 0 || view.height <= 0) {
                result.complete(null)
            } else {
                view.getMapAsync { map ->
                    try {
                        assertTrue("지도 readback은 UI 스레드에서만 실행합니다", Looper.myLooper() == Looper.getMainLooper())
                        if (!view.isAttachedToWindow || currentMapViewOnMain() !== view) {
                            result.complete(null)
                        } else {
                            val style = map.style
                            val source = style?.getSource(SOURCE_ID) as? GeoJsonSource
                            val circles = style?.getLayer(CIRCLES_ID) as? CircleLayer
                            val numbers = style?.getLayer(NUMBERS_ID) as? SymbolLayer
                            val styleReady = style != null && style.isFullyLoaded && style.uri == STYLE_URI
                            val layerIdsMatch =
                                circles != null && numbers != null &&
                                    circles.sourceId == SOURCE_ID && numbers.sourceId == SOURCE_ID
                            val layersVisible = circles?.visibility?.value != "none" && numbers?.visibility?.value != "none"
                            var valid = styleReady && source != null && layerIdsMatch && layersVisible
                            val features = source?.querySourceFeatures(null).orEmpty()
                            val ids = features.map { it.getStringProperty("point_id") }.toSet()
                            valid = valid && ids == preview.points.map { it.id }.toSet() && preview.points.size == 3
                            for (feature in features) {
                                val index = preview.points.indexOfFirst { it.id == feature.getStringProperty("point_id") }
                                if (index < 0) {
                                    valid = false
                                    break
                                }
                                val expected = preview.points[index]
                                val point = feature.geometry() as? Point
                                val sameOrder = feature.getNumberProperty("order").toDouble() == (index + 1).toDouble()
                                val sameId = feature.getStringProperty("preview_id") == preview.id
                                val actualVersion = feature.getNumberProperty("preview_version").toDouble()
                                val sameVersion = actualVersion == preview.version.toDouble()
                                valid = valid && point != null && feature.getStringProperty("title") == expected.title &&
                                    sameOrder && sameId && sameVersion
                                if (point != null) {
                                    val actual = map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                                    val expectedLocation = LatLng(expected.latitude, expected.longitude)
                                    val expectedScreen = map.projection.toScreenLocation(expectedLocation)
                                    val margin = 8f * view.resources.displayMetrics.density
                                    val projectionMatches =
                                        abs(actual.x - expectedScreen.x) <= 1f &&
                                            abs(actual.y - expectedScreen.y) <= 1f
                                    val insideView =
                                        expectedScreen.x >= margin && expectedScreen.y >= margin &&
                                            expectedScreen.x <= view.width - margin && expectedScreen.y <= view.height - margin
                                    valid = valid && projectionMatches && insideView
                                }
                            }
                            val padding = (40 * view.resources.displayMetrics.density).roundToInt()
                            val bounds =
                                LatLngBounds
                                    .Builder()
                                    .includes(preview.points.map { LatLng(it.latitude, it.longitude) })
                                    .build()
                            val fit = CameraUpdateFactory.newLatLngBounds(bounds, padding).getCameraPosition(map)
                            val camera = map.cameraPosition
                            val target = camera.target
                            val fitTarget = fit?.target
                            valid = valid && target != null && fitTarget != null
                            if (target != null && fitTarget != null && fit != null) {
                                val actualTarget = map.projection.toScreenLocation(target)
                                val expectedTarget = map.projection.toScreenLocation(fitTarget)
                                val targetMatches =
                                    abs(actualTarget.x - expectedTarget.x) <= 1f &&
                                        abs(actualTarget.y - expectedTarget.y) <= 1f
                                val zoomMatches = abs(camera.zoom - fit.zoom) <= 0.001
                                val orientationMatches =
                                    abs(camera.bearing - fit.bearing) <= 0.001 &&
                                        abs(camera.tilt - fit.tilt) <= 0.001
                                valid = valid && targetMatches && zoomMatches && orientationMatches
                            }
                            result.complete(
                                if (valid && target != null) {
                                    NativeReadback(
                                        view,
                                        CameraSnapshot(target.latitude, target.longitude, camera.zoom, camera.bearing, camera.tilt),
                                        features.size,
                                    )
                                } else {
                                    null
                                },
                            )
                        }
                    } catch (_: Throwable) {
                        result.completeExceptionally(AssertionError("실제 native style/source/고정 preview camera를 확인하지 못했습니다"))
                    }
                }
            }
        }
        return withTimeout(timeoutMs) { result.await() }
    }

    private fun currentMapView(): MapView? {
        var result: MapView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = currentMapViewOnMain() }
        return result
    }

    private fun currentMapViewOnMain(): MapView? =
        mapViews(compose.activity.window.decorView)
            .filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }
            .singleOrNull()

    private fun mapViews(view: View): List<MapView> =
        when (view) {
            is MapView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { mapViews(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun nativeJson(readback: NativeReadback): JSONObject =
        JSONObject()
            .put("validated", true)
            .put("styleUri", STYLE_URI)
            .put("logicalPointCount", 3)
            .put("featureCopies", readback.featureCopies)
            .put(
                "camera",
                JSONObject()
                    .put("latitude", readback.camera.latitude)
                    .put("longitude", readback.camera.longitude)
                    .put("zoom", readback.camera.zoom)
                    .put("bearing", readback.camera.bearing)
                    .put("tilt", readback.camera.tilt),
            )

    private fun previewSourceJson(
        selection: PracticeSelection,
        prepared: PreparedPractice,
        preview: CourseMapPreview,
    ): JSONObject =
        JSONObject()
            .put("selection", selectionJson(selection))
            .put("preparedSnapshot", prepared.toString())
            .put(
                "preview",
                JSONObject()
                    .put("id", preview.id)
                    .put("version", preview.version)
                    .put("title", preview.title)
                    .put("source", preview.source.name)
                    .put("points", JSONArray(preview.points.map { it.toString() })),
            )

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { nodes(tag).size == 1 }
    }

    private fun scrollVisible(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        val viewport = checkNotNull(appViewport())
        assertTrue("조작 전에 실제 앱 가용 화면에 표시돼야 합니다", fullyVisible(nodes(tag).single(), viewport))
    }

    private fun appViewport(): AppViewport? {
        var viewport: AppViewport? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decor = compose.activity.window.decorView
            val screen = IntArray(2)
            val window = IntArray(2)
            decor.getLocationOnScreen(screen)
            decor.getLocationInWindow(window)
            val available = Rect(screen[0], screen[1], screen[0] + decor.width, screen[1] + decor.height)
            val visible = Rect()
            decor.getWindowVisibleDisplayFrame(visible)
            if (available.intersect(visible) && available.width() > 0 && available.height() > 0) {
                viewport = AppViewport(available, screen[0] - window[0], screen[1] - window[1])
            }
        }
        return viewport
    }

    private fun fullyVisible(
        node: SemanticsNode,
        viewport: AppViewport,
    ): Boolean {
        if (node.size.width <= 0 || node.size.height <= 0) return false
        val bounds = node.boundsInWindow
        return bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
            bounds.left + viewport.originX >= viewport.available.left - 1 &&
            bounds.top + viewport.originY >= viewport.available.top - 1 &&
            bounds.right + viewport.originX <= viewport.available.right + 1 &&
            bounds.bottom + viewport.originY <= viewport.available.bottom + 1
    }

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(30_000) {
            val sessions = graph.sessions().observeSessions().first()
            ExistingData(
                graph.preparation().observeSelection().first(),
                sessions,
                sessions.associate { it.sessionId to graph.observations().observe(it.sessionId).first() },
                sessions.associate { it.sessionId to graph.memos().observe(it.sessionId).first() },
            )
        }

    private suspend fun assertPreserved(
        graph: RecordingTestEntryPoint,
        before: ExistingData,
    ) {
        val after = snapshot(graph)
        assertTrue("원래 준비의 모든 필드를 보존해야 합니다", after.preparation == before.preparation)
        assertTrue("모든 세션 필드와 저장 생성 순서를 보존해야 합니다", after.sessions == before.sessions)
        assertTrue("모든 관찰 필드와 순서를 보존해야 합니다", after.observations == before.observations)
        assertTrue("모든 메모 원문과 변경 토큰을 보존해야 합니다", after.memos == before.memos)
        assertTrue("읽기 계측으로 활성 연습이 생기면 안 됩니다", after.sessions.none { it.isActive })
    }

    private fun measurementContract(): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("method", "MapOpeningMeasureTest.measureMapOpeningAndPreserveOriginalData")
            .put("clock", "SystemClock.elapsedRealtimeNanos")
            .put("unit", "ms")
            .put("warmups", WARMUPS)
            .put("samples", SAMPLES)
            .put("start", "directly before home_map performClick")
            .put("endpoint", "map_ready plus full map_view and native style/source/fit-camera, Compose idle and native recheck")
            .put("endpointTimeoutNs", ENDPOINT_TIMEOUT_NS)
            .put("endpointTimeoutIncludesClickAndComposeBlocking", true)
            .put("composeBlockingTimeoutIsNotAnInterruptibleDeadline", true)
            .put(
                "included",
                JSONArray(
                    listOf(
                        "click dispatch",
                        "map_view performScrollTo",
                        "native renderer/style/tile loading",
                        "native style/source/fit-camera queries",
                        "Compose synchronization/idle",
                        "semantics queries",
                    ),
                ),
            ).put(
                "excluded",
                JSONArray(
                    listOf(
                        "home pre-scroll",
                        "SDK gate/original data/catalog/environment checks",
                        "post-endpoint source text",
                        "JSON",
                        "close",
                        "Activity/process startup",
                    ),
                ),
            ).put("cacheCondition", "fresh externally started process and Activity; five warmups; no cache clearing")
            .put("order", "five warmups then thirty map openings; return to same home/selection/preview between calls")
            .put("build", "same API34 debug/font1.0/light and frozen catalog/data/environment")
            .put("harnessSourceProvenance", "external declared full SHA; runner must freeze this source across both versions")
            .put("performanceScope", "instrumented map UI opening wall time; not pure render/frame/GPU/SDK/user speed")
            .put("nativeCriteria", "public style/fullyLoaded, exact3 points/layers, tile projection within1px, SDK fit camera")
            .put("styleUri", STYLE_URI)
            .put("networkCacheLimitation", "real OpenFreeMap/unchanged cache; sequential runs do not isolate cache or network")

    private fun environmentJson(): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = context.resources.configuration
        return JSONObject()
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("fontScale", configuration.fontScale.toDouble())
            .put("uiMode", configuration.uiMode)
            .put("screenWidthDp", configuration.screenWidthDp)
            .put("screenHeightDp", configuration.screenHeightDp)
            .put("densityDpi", configuration.densityDpi)
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("timeZone", ZoneId.systemDefault().id)
            .put("debuggable", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("productPackage", context.packageName)
            .put("activityOpenedByHarness", true)
            .put(
                "appViewport",
                checkNotNull(appViewport()).let { viewport ->
                    JSONObject()
                        .put("left", viewport.available.left)
                        .put("top", viewport.available.top)
                        .put("right", viewport.available.right)
                        .put("bottom", viewport.available.bottom)
                        .put("originX", viewport.originX)
                        .put("originY", viewport.originY)
                },
            )
    }

    private fun catalogJson(graph: RecordingTestEntryPoint): JSONObject =
        JSONObject()
            .put("goalsInOrder", JSONArray(graph.catalog().goals.map { it.toString() }))
            .put("coursesInOrder", JSONArray(graph.catalog().courses.map { it.toString() }))

    private fun completedBaselineHash(file: File): String {
        assertTrue(
            "완료 기준 원문과 미완료 sidecar를 구분해야 합니다",
            file.isFile && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(),
        )
        return sha256(file)
    }

    private fun readBaseline(file: File): JSONObject =
        try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            throw AssertionError("비공개 완료 기준 원문을 읽지 못했습니다")
        }

    private fun validateBaseline(baseline: JSONObject) {
        assertTrue(
            "완료된 같은 지도 진입 기준이어야 합니다",
            baseline.getInt("schema") == 1 && baseline.getString("kind") == "map_point_tap_entry" &&
                baseline.getString("measurementStage") == "baseline" && baseline.getString("status") == "completed" &&
                baseline.getBoolean("preservedOriginalData") && baseline.getBoolean("returnedHome") &&
                baseline.getInt("excludedSamples") == 0,
        )
        assertTrue(
            "기준 원본의 선언이 완전해야 합니다",
            baseline.getString("declaredProductCommit").matches(Regex("[0-9a-f]{40}")) &&
                baseline.getString("declaredHarnessRevision").matches(Regex("[0-9a-f]{40}")),
        )
        assertTrue(
            "기준 반복 횟수가 같아야 합니다",
            baseline.getInt("plannedWarmups") == WARMUPS && baseline.getInt("plannedSamples") == SAMPLES,
        )
        requireSameJson(baseline.getJSONObject("measurementContract"), measurementContract(), "baselineContract")
        for ((name, planned) in listOf("warmups" to WARMUPS, "samples" to SAMPLES)) {
            val values = baseline.getJSONArray(name)
            assertTrue("기준 원값이 모두 성공이어야 합니다", values.length() == planned && successful(values) == planned)
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                assertTrue("기준 원값과 순서가 빠지면 안 됩니다", value.getInt("index") == index + 1 && !value.isNull("elapsedNs"))
                assertTrue(
                    "기준 시간과 단위가 실제 범위여야 합니다",
                    value.getLong("elapsedNs") in 0..ENDPOINT_TIMEOUT_NS &&
                        value.getDouble("elapsedMs") == value.getLong("elapsedNs") / NANOS_PER_MS,
                )
                assertTrue(
                    "기준 endpoint와 선택/홈 복귀를 확인해야 합니다",
                    value.getBoolean("endpointConfirmed") && value.getBoolean("sourceMatched") && value.getBoolean("returnedHome"),
                )
                val native = value.getJSONObject("nativeReadback")
                assertTrue(
                    "기준도 실제 native source/camera를 대조했어야 합니다",
                    native.getBoolean("validated") && native.getString("styleUri") == STYLE_URI &&
                        native.getInt("logicalPointCount") == 3 && native.getInt("featureCopies") >= 3,
                )
            }
            requireSameJson(
                baseline.getJSONObject(if (name == "warmups") "warmupCounts" else "sampleCounts"),
                counts(values, planned),
                name,
            )
        }
        requireSameJson(baseline.getJSONObject("statistics"), statistics(baseline.getJSONArray("samples")), "baselineStatistics")
    }

    private fun reserve(file: File) {
        assertTrue(
            "기존 근거와 미완료 측정 파일을 덮으면 안 됩니다",
            !file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(),
        )
        assertTrue("비공개 측정 폴더를 준비해야 합니다", file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        assertTrue("이번 실행의 소유 파일만 예약해야 합니다", file.createNewFile())
    }

    private fun write(
        file: File,
        report: JSONObject,
    ) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }

    private fun requireSameJson(
        expected: Any?,
        actual: Any?,
        field: String,
    ) {
        assertTrue("기준과 비교의 고정 조건이 같아야 합니다 ($field)", sameJson(expected, actual))
    }

    private fun sameJson(
        expected: Any?,
        actual: Any?,
    ): Boolean {
        if (expected == null || expected === JSONObject.NULL) return actual == null || actual === JSONObject.NULL
        if (actual == null || actual === JSONObject.NULL) return false
        if (expected is JSONObject && actual is JSONObject) {
            val expectedKeys = expected.keys().asSequence().toSet()
            val actualKeys = actual.keys().asSequence().toSet()
            return expectedKeys == actualKeys && expectedKeys.all { sameJson(expected.get(it), actual.get(it)) }
        }
        if (expected is JSONArray && actual is JSONArray) {
            return expected.length() == actual.length() && (0 until expected.length()).all { sameJson(expected.get(it), actual.get(it)) }
        }
        if (expected is Number && actual is Number) {
            return try {
                BigDecimal(expected.toString()).compareTo(BigDecimal(actual.toString())) == 0
            } catch (_: NumberFormatException) {
                false
            }
        }
        return expected == actual
    }

    private fun successful(array: JSONArray): Int =
        (0 until array.length()).count { array.getJSONObject(it).optString("status") == "success" }

    private fun counts(
        array: JSONArray,
        planned: Int,
    ): JSONObject =
        JSONObject()
            .put("successful", successful(array))
            .put("failed", (0 until array.length()).count { array.getJSONObject(it).optString("status") == "failed" })
            .put("missingDuration", (0 until array.length()).count { array.getJSONObject(it).isNull("elapsedNs") })
            .put("notRun", planned - array.length())

    private fun statistics(samples: JSONArray): JSONObject {
        assertTrue("완전한 실제 원값만 집계해야 합니다", samples.length() == SAMPLES && successful(samples) == SAMPLES)
        val sorted = (0 until samples.length()).map { samples.getJSONObject(it).getLong("elapsedNs") / NANOS_PER_MS }.sorted()

        fun median(values: List<Double>): Double =
            if (values.size % 2 == 0) {
                (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
            } else {
                values[values.size / 2]
            }
        val q1 = median(sorted.take(sorted.size / 2))
        val q3 = median(sorted.drop(sorted.size / 2))
        return JSONObject()
            .put("unit", "ms")
            .put("sampleCount", sorted.size)
            .put("median", median(sorted))
            .put("q1", q1)
            .put("q3", q3)
            .put("interquartileRange", q3 - q1)
            .put("min", sorted.first())
            .put("max", sorted.last())
            .put("quartileMethod", "median_of_each_sorted_half")
            .put("zeroDurationSamples", sorted.count { it == 0.0 })
            .put("causalPerformanceClaim", false)
    }

    private fun dataJson(data: ExistingData): JSONObject =
        JSONObject()
            .put("preparation", data.preparation?.let(::selectionJson) ?: JSONObject.NULL)
            .put("sessions", JSONArray(data.sessions.map(::sessionJson)))
            .put(
                "observations",
                JSONArray(
                    data.observations.map { (sessionId, rows) ->
                        JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "rows",
                                JSONArray(
                                    rows.map { row ->
                                        JSONObject()
                                            .put("id", row.id)
                                            .put("sessionId", row.sessionId)
                                            .put("observedAtEpochMillis", row.observation.observedAtEpochMillis)
                                            .put("source", row.observation.source.name)
                                            .put("connection", row.observation.state.connection.name)
                                            .put("gear", readingJson(row.observation.state.gear))
                                            .put("turnSignal", readingJson(row.observation.state.turnSignal))
                                            .put("brakePressed", readingJson(row.observation.state.brakePressed))
                                    },
                                ),
                            )
                    },
                ),
            ).put(
                "memos",
                JSONArray(
                    data.memos.map { (sessionId, memo) ->
                        JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "memo",
                                memo?.let { JSONObject().put("sessionId", it.sessionId).put("text", it.text).put("version", it.version) }
                                    ?: JSONObject.NULL,
                            )
                    },
                ),
            )

    private fun selectionJson(selection: PracticeSelection): JSONObject =
        JSONObject()
            .put("goalId", selection.goalId)
            .put("courseId", selection.courseId)
            .put("courseVersion", selection.courseVersion)
            .put("briefingVersion", selection.briefingVersion)
            .put("taskId", selection.taskId)

    private fun sessionJson(session: PracticeSession): JSONObject =
        JSONObject()
            .put("sessionId", session.sessionId)
            .put("selection", selectionJson(session.selection))
            .put("goalTitle", session.goalTitle)
            .put("courseTitle", session.courseTitle)
            .put("taskTitle", session.taskTitle)
            .put("courseReviewStatus", session.courseReviewStatus.name)
            .put("startedAtEpochMillis", session.startedAtEpochMillis)
            .put("endedAtEpochMillis", session.endedAtEpochMillis ?: JSONObject.NULL)

    private fun <T : Any> readingJson(reading: VehicleReading<T>): JSONObject =
        when (reading) {
            is VehicleReading.Available ->
                JSONObject().put("status", "AVAILABLE").put(
                    "value",
                    when (val value = reading.value) {
                        is Enum<*> -> value.name
                        else -> value
                    },
                )
            VehicleReading.Unknown -> JSONObject().put("status", "UNKNOWN")
            VehicleReading.Unsupported -> JSONObject().put("status", "UNSUPPORTED")
            VehicleReading.PermissionDenied -> JSONObject().put("status", "PERMISSION_DENIED")
            VehicleReading.Error -> JSONObject().put("status", "ERROR")
        }

    private data class CameraSnapshot(
        val latitude: Double,
        val longitude: Double,
        val zoom: Double,
        val bearing: Double,
        val tilt: Double,
    )

    private data class NativeReadback(
        val view: MapView,
        val camera: CameraSnapshot,
        val featureCopies: Int,
    )

    private data class AppViewport(
        val available: Rect,
        val originX: Int,
        val originY: Int,
    )

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
    )

    private companion object {
        const val WARMUPS = 5
        const val SAMPLES = 30
        const val NANOS_PER_MS = 1_000_000.0
        const val ENDPOINT_TIMEOUT_NS = 30_000_000_000L
        const val SOURCE_ID = "hangil-course-preview-points"
        const val CIRCLES_ID = "hangil-course-preview-circles"
        const val NUMBERS_ID = "hangil-course-preview-numbers"
        const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
    }
}
