package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.GearPosition
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
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import io.github.hangillab.hangil.feature.map.R as MapR

/**
 * Explicit actual Main/SDK/native number taps on the currently prepared fixed three-point preview.
 * No preparation, session, observation, memo, permission, camera or setting is written by this test.
 * The external owner preflights the actual DDS bridge/P/UX and preserves raw SQLite/settings.
 * Domain snapshots below compare all fields/order, not raw SQLite types or sequences. Fixed OSM
 * center points are not GPS, road sections or destinations. Style filter/ACK is not a GPU/frame
 * completion or timing claim. Private real UI crops permit separate rendered-highlight review.
 * Old-UP lifecycle checks may overlap elapsed-time rejection; isolated input controls are separate.
 */
@RunWith(AndroidJUnit4::class)
class MapPointTapFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val nativeCallSequence = AtomicInteger(0)
    private val lastNativeSnapshot = AtomicReference<String?>(null)
    private val nativeLoopHistory = ArrayDeque<String>()
    private val pendingTouch = AtomicReference<TapToken?>(null)
    private val inputEvents = JSONArray()

    @Test fun nativePointTapsMatchFixedHighlightAndExpireAcrossOwnerAndDrivingChanges() {
        assumeTrue(
            "실제 SDK와 native 번호 탭/원본 보존을 준비한 명시 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualMapPointTap") == "true",
        )
        verifyConfiguration()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("전용 debug 앱에서만 원래 그래프를 읽습니다", context.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("debug 빌드가 필요합니다", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val directory = File(context.cacheDir, "screenshots/map-point-tap-${UUID.randomUUID()}")
        assertTrue("이 실행의 새 비공개 캡처 폴더만 만듭니다", directory.mkdirs())
        val pointChecks = JSONArray()
        val boundaryChecks = JSONArray()
        val evidence =
            JSONObject()
                .put("schema", 1)
                .put("pointChecks", pointChecks)
                .put("boundaryChecks", boundaryChecks)
                .put("inputEvents", inputEvents)
                .put("scenarioCompleted", false)
        runBlocking {
            val before = snapshot(graph)
            assertTrue("원래 활성 연습이 있으면 지도 검사를 시작하지 않습니다", before.sessions.none { it.isActive })
            val prepared =
                checkNotNull((graph.catalog().resolve(before.preparation) as? Preparation.Ready)?.practice) {
                    "원래 준비를 현재 카탈로그에서 확인해야 합니다"
                }
            val preview = checkNotNull(prepared.course.mapPreview) { "원래 코스의 고정 시연 preview가 필요합니다" }
            assertTrue(
                "현재 원래 코스의 서로 다른 세 지점만 검사합니다",
                preview.points.size == 3 && preview.points
                    .map { it.id }
                    .distinct()
                    .size == 3,
            )
            val observer =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primary: Throwable? = null
            try {
                awaitAllowed(graph)
                requireForeground()
                touch("home_map")
                val initial = assertInitial(prepared, preview)
                for ((index, point) in preview.points.withIndex()) {
                    bringIntoView("map_view")
                    val selectedBefore = if (index == 0) null else preview.points[index - 1].id
                    val beforeTap = awaitNative(preview, selectedBefore, initial.view, "before_native_tap")
                    assertTrue("탭 전 camera는 원래 기준과 같아야 합니다", sameCamera(initial.camera, beforeTap.camera))
                    val check = JSONObject().put("index", index).put("selectionVerified", false)
                    pointChecks.put(check)
                    val token = beginNativeTap(preview, index, initial.view, "fresh")
                    delay(40)
                    finishNativeTap(token, stale = false, boundary = "fresh")
                    val selected = awaitSelection(preview, index, initial.view)
                    val unchanged = sameCamera(initial.camera, selected.camera)
                    check.put("nativeFilterConfirmed", true).put("cameraUnchanged", unchanged)
                    writeTapEvidence(directory, evidence)
                    assertTrue("번호 탭은 camera를 이동하면 안 됩니다", unchanged)
                    assertSelectedDetails(point.title)
                    assertPreserved(graph, before)
                    check.put("displayTextConfirmed", true).put("selectionVerified", true)
                    writeTapEvidence(directory, evidence)
                    capture(directory, "point-$index-number-tap-map", "map_view")
                    capture(directory, "point-$index-number-tap-details", "map_selected_point")
                    capture(directory, "point-$index-number-tap-choice", "map_point_$index")
                }
                // The existing accessible list remains an independent alternative to native taps.
                touch("map_point_1")
                val fallback = awaitSelection(preview, 1, initial.view, "after_list_fallback")
                assertTrue("목록 대체 선택도 camera를 바꾸면 안 됩니다", sameCamera(initial.camera, fallback.camera))
                evidence.put("listFallbackVerified", true)
                assertSelectedDetails(preview.points[1].title)

                val beforeRecreate = beginNativeTap(preview, 0, initial.view, "recreate")
                compose.activityRule.scenario.recreate()
                val recreated = assertInitial(prepared, preview)
                finishNativeTap(beforeRecreate, stale = true, boundary = "recreate")
                assertOldUpRejected(prepared, preview, recreated, "recreate", boundaryChecks)
                freshTapAndCheck(preview, 0, recreated)

                val beforeClose = beginNativeTap(preview, 1, recreated.view, "close_reenter")
                // Closing via the real semantics action avoids introducing a second pointer stream.
                activateSemantics("map_close")
                waitFor("home_map")
                activateSemantics("home_map")
                val reentered = assertInitial(prepared, preview)
                finishNativeTap(beforeClose, stale = true, boundary = "close_reenter")
                assertOldUpRejected(prepared, preview, reentered, "close_reenter", boundaryChecks)
                freshTapAndCheck(preview, 1, reentered)

                val beforeStop = beginNativeTap(preview, 2, reentered.view, "stop_start")
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                val resumed = assertInitial(prepared, preview)
                finishNativeTap(beforeStop, stale = true, boundary = "stop_start")
                assertOldUpRejected(prepared, preview, resumed, "stop_start", boundaryChecks)
                freshTapAndCheck(preview, 2, resumed)
                writeTapEvidence(directory, evidence)
                assertPreserved(graph, before)

                phase("POINT_TAP_AWAIT_D")
                withTimeout(90_000) {
                    graph.vehicle().state.first {
                        it.connection == ConnectionState.CONNECTED && it.gear == VehicleReading.Available(GearPosition.DRIVE)
                    }
                }
                waitFor("map_locked")
                assertNoSelectionDetails()
                listOf("map_point_0", "map_point_1", "map_point_2", "map_point_selection_instruction", "map_view", "map_ready")
                    .forEach { assertTrue("D에서 지도 상세/선택/설명을 남기면 안 됩니다", nodes(it).isEmpty()) }
                assertPreserved(graph, before)
                phase("POINT_TAP_AWAIT_P")
                awaitAllowed(graph)
                val returned = assertInitial(prepared, preview)
                freshTapAndCheck(preview, 0, returned)
                capture(directory, "returned-native-tap-map", "map_view")
                touch("map_close")
                waitFor("home_map")
                assertNoSelectionDetails()
                assertPreserved(graph, before)
            } catch (failure: Throwable) {
                primary = failure
                preserveFailure(directory, failure)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    val cleanupFailures = mutableListOf<Throwable>()
                    try {
                        cancelPendingTouch()
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    }
                    try {
                        if (nodes("map_close").size == 1) touch("map_close")
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    }
                    try {
                        assertPreserved(graph, before)
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    } finally {
                        observer.cancelAndJoin()
                    }
                    try {
                        writeTapEvidence(directory, evidence)
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    }
                    if (cleanupFailures.isNotEmpty()) {
                        val original = primary
                        if (original != null) {
                            cleanupFailures.filterNot { it === original }.forEach(original::addSuppressed)
                        } else {
                            val first = cleanupFailures.first()
                            cleanupFailures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                            throw first
                        }
                    }
                }
            }
            evidence.put("scenarioCompleted", true)
            writeTapEvidence(directory, evidence)
            phase("POINT_TAP_COMPLETED")
        }
    }

    /** Real SDK/style/projection geometry only; this is not a timing or geographic-accuracy measure. */
    @Test fun selectionOutlineHasFourDpViewportClearance() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "실제 확대 다크 지도 테두리와 원본 보존을 준비한 명시 실행에서만 검사합니다",
            arguments.getString("manualMapPointTap") == "true",
        )
        verifyConfiguration()
        assertTrue(
            "이 집중 검사는 명시한 글꼴1.3/다크에서 실행해야 합니다",
            arguments.getString("expectedFontScale")?.toFloatOrNull() == 1.3f &&
                arguments.getString("expectedDark")?.toBooleanStrictOrNull() == true,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("전용 debug 앱에서만 원래 그래프를 읽습니다", context.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("debug 빌드가 필요합니다", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val directory = File(context.cacheDir, "screenshots/map-outline-clearance-${UUID.randomUUID()}")
        assertTrue("이 실행의 새 비공개 진단 폴더만 만듭니다", directory.mkdirs())
        runBlocking {
            val before = snapshot(graph)
            assertTrue("원래 활성 연습이 있으면 테두리 검사를 시작하지 않습니다", before.sessions.none { it.isActive })
            val prepared =
                checkNotNull((graph.catalog().resolve(before.preparation) as? Preparation.Ready)?.practice) {
                    "원래 준비를 현재 카탈로그에서 확인해야 합니다"
                }
            val preview = checkNotNull(prepared.course.mapPreview) { "원래 코스의 고정 시연 preview가 필요합니다" }
            assertTrue(
                "서로 다른 원래 세 지점의 테두리를 모두 확인합니다",
                preview.points.size == 3 && preview.points
                    .map { it.id }
                    .distinct()
                    .size == 3,
            )
            val observer =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primary: Throwable? = null
            try {
                awaitAllowed(graph)
                requireForeground()
                touch("home_map")
                val initial = assertInitial(prepared, preview)
                val token = beginNativeTap(preview, 2, initial.view, "outline_clearance")
                delay(40)
                finishNativeTap(token, stale = false, boundary = "outline_clearance")
                val selected = awaitSelection(preview, 2, initial.view, "outline_clearance_selected")
                assertTrue("번호 선택이 원래 초기 camera를 바꾸면 안 됩니다", sameCamera(initial.camera, selected.camera))
                bringIntoView("map_view")
                compose.waitForIdle()
                requireForeground()
                assertTrue("테두리는 실제 지도 전체가 화면 안에 있을 때 대조합니다", fullyVisible(nodes("map_view").single()))
                assertTrue(
                    "실제 P와 UX 허용에서만 테두리를 읽습니다",
                    allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value),
                )
                val measured = measureOutlineClearance(preview, selected.view, preview.points[2].id)
                assertTrue(
                    "테두리 대조 뒤에도 실제 P와 UX 허용이어야 합니다",
                    allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value),
                )
                File(directory, "clearance.json").writeText(measured.toString(2))
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("stream", "POINT_TAP_OUTLINE_CLEARANCE\n")
                        putString("outlineClearance", measured.toString())
                    },
                )
                assertTrue(
                    "세 지점의 선택 바깥 테두리는 지도 네 가장자리에서 최소4dp 떨어져야 합니다",
                    measured.getDouble("minClearancePx") + 0.001 >= measured.getDouble("requiredClearancePx"),
                )
                capture(directory, "selection-outline", "map_view")
                assertPreserved(graph, before)
            } catch (failure: Throwable) {
                primary = failure
                preserveFailure(directory, failure)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    val cleanupFailures = mutableListOf<Throwable>()
                    try {
                        cancelPendingTouch()
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    }
                    try {
                        if (nodes("map_close").size == 1) touch("map_close")
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    }
                    try {
                        assertPreserved(graph, before)
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    }
                    try {
                        observer.cancelAndJoin()
                    } catch (cleanup: Throwable) {
                        cleanupFailures.add(cleanup)
                    }
                    if (cleanupFailures.isNotEmpty()) {
                        val original = primary
                        if (original != null) {
                            cleanupFailures.filterNot { it === original }.forEach(original::addSuppressed)
                        } else {
                            val first = cleanupFailures.first()
                            cleanupFailures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                            throw first
                        }
                    }
                }
            }
        }
    }

    /** Style radius/stroke units use the SDK pixel ratio; projection and View dimensions are Android px. */
    private suspend fun measureOutlineClearance(
        preview: CourseMapPreview,
        expectedView: MapView,
        selectedId: String,
    ): JSONObject =
        withTimeout(5_000) {
            val result = CompletableDeferred<JSONObject>()
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    if (!result.isActive) return@runOnMainSync
                    try {
                        assertTrue(
                            "테두리 JNI는 현재 부착 View에서만 조회합니다",
                            expectedView.isAttachedToWindow && currentMapViewOnMain() === expectedView,
                        )
                        expectedView.getMapAsync { map ->
                            if (!result.isActive) return@getMapAsync
                            try {
                                assertTrue("테두리 JNI는 Main에서만 읽습니다", Looper.myLooper() == Looper.getMainLooper())
                                assertTrue(
                                    "콜백에서도 같은 현재 부착 View여야 합니다",
                                    expectedView.isAttachedToWindow && currentMapViewOnMain() === expectedView,
                                )
                                val visible = Rect()
                                assertTrue(
                                    "실제 native View의 네 가장자리도 모두 보여야 합니다",
                                    expectedView.isShown && expectedView.width > 0 && expectedView.height > 0 &&
                                        expectedView.getLocalVisibleRect(visible) && visible.left == 0 && visible.top == 0 &&
                                        visible.right == expectedView.width && visible.bottom == expectedView.height,
                                )
                                val style = checkNotNull(map.style) { "현재 준비된 style이 필요합니다" }
                                assertTrue("현재 style이 완전히 로드돼야 합니다", style.isFullyLoaded)
                                val layer = checkNotNull(style.getLayerAs<CircleLayer>(SELECTION_ID))
                                assertTrue("선택 테두리는 같은 source여야 합니다", layer.sourceId == SOURCE_ID)
                                assertTrue(
                                    "실제 적용된 선택 filter도 같은 세 번째 지점이어야 합니다",
                                    layer.filter?.toString() ==
                                        Expression.eq(Expression.get("point_id"), Expression.literal(selectedId)).toString(),
                                )
                                val radius = checkNotNull(layer.circleRadius.value).toDouble()
                                val stroke = checkNotNull(layer.circleStrokeWidth.value).toDouble()
                                val density =
                                    expectedView.resources.displayMetrics.density
                                        .toDouble()
                                assertTrue(
                                    "실제 반경과 stroke는 유한한 음수 아닌 값이어야 합니다",
                                    radius.isFinite() && radius >= 0 && stroke.isFinite() && stroke >= 0,
                                )
                                assertTrue("실제 화면 density가 필요합니다", density.isFinite() && density > 0)
                                val outerRadiusPx = (radius + stroke) * density
                                val requiredClearancePx = 4.0 * density
                                val circles = checkNotNull(style.getLayerAs<CircleLayer>(CIRCLES_ID))
                                assertTrue("현재 hit layer도 같은 source여야 합니다", circles.sourceId == SOURCE_ID)
                                assertTrue(
                                    "현재 native 목록에 같은 GeoJSON source 하나가 있어야 합니다",
                                    style.sources.singleOrNull { it.id == SOURCE_ID } is GeoJsonSource,
                                )
                                var minClearancePx = Double.POSITIVE_INFINITY
                                for ((index, point) in preview.points.withIndex()) {
                                    val planned = map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                                    assertTrue("계획 좌표의 projection은 유한해야 합니다", planned.x.isFinite() && planned.y.isFinite())
                                    val hits = map.queryRenderedFeatures(planned, CIRCLES_ID)
                                    assertTrue("각 지점의 실제 현재 rendered circle이 있어야 합니다", hits.isNotEmpty())
                                    for (feature in hits) {
                                        val properties = checkNotNull(feature.properties()) { "실제 hit metadata가 필요합니다" }
                                        for (
                                        (key, expected) in
                                        mapOf("point_id" to point.id, "title" to point.title, "preview_id" to preview.id)
                                        ) {
                                            val raw = checkNotNull(properties.get(key)) { "실제 hit의 문자열 metadata가 필요합니다" }
                                            assertTrue(
                                                "후보의 문자열 형식과 값이 같은 원래 지점이어야 합니다",
                                                raw.isJsonPrimitive && raw.asJsonPrimitive.isString && raw.asString == expected,
                                            )
                                        }
                                        for ((key, expected) in mapOf("preview_version" to preview.version, "order" to index + 1)) {
                                            val raw = checkNotNull(properties.get(key)) { "실제 hit의 숫자 metadata가 필요합니다" }
                                            assertTrue(
                                                "후보의 숫자는 문자열 대체 없이 같은 version/순서여야 합니다",
                                                raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber &&
                                                    raw.asBigDecimal.compareTo(java.math.BigDecimal.valueOf(expected.toLong())) == 0,
                                            )
                                        }
                                        val geometry = checkNotNull(feature.geometry() as? Point) { "실제 hit는 Point여야 합니다" }
                                        assertTrue("실제 Point는 두 좌표여야 합니다", geometry.coordinates().size == 2)
                                        val latitude = geometry.latitude()
                                        val longitude = geometry.longitude()
                                        assertTrue(
                                            "실제 Point는 유한한 위경도 범위여야 합니다",
                                            latitude.isFinite() && latitude in -90.0..90.0 &&
                                                longitude.isFinite() && longitude in -180.0..180.0,
                                        )
                                        val projected = map.projection.toScreenLocation(LatLng(latitude, longitude))
                                        assertTrue(
                                            "실제 rendered geometry의 projection은 유한해야 합니다",
                                            projected.x.isFinite() && projected.y.isFinite(),
                                        )
                                        assertTrue(
                                            "현재 hit geometry는 고정 지점의 화면 중심과2px 이내여야 합니다",
                                            kotlin.math.hypot(
                                                (projected.x - planned.x).toDouble(),
                                                (projected.y - planned.y).toDouble(),
                                            ) <= 2.0,
                                        )
                                        // Valid tile/world duplicates of this same ID are conservative:
                                        // use the minimum current screen clearance, never a planned raw center.
                                        val clearance =
                                            listOf(
                                                projected.x.toDouble() - outerRadiusPx,
                                                expectedView.width.toDouble() - projected.x - outerRadiusPx,
                                                projected.y.toDouble() - outerRadiusPx,
                                                expectedView.height.toDouble() - projected.y - outerRadiusPx,
                                            ).minOrNull()
                                        minClearancePx = minOf(minClearancePx, checkNotNull(clearance))
                                    }
                                }
                                assertTrue(
                                    "같은 style과 현재 View에서 대조를 마쳐야 합니다",
                                    map.style === style && currentMapViewOnMain() === expectedView,
                                )
                                assertTrue("실제 세 지점의 유한한 여백이 필요합니다", preview.points.size == 3 && minClearancePx.isFinite())
                                result.complete(
                                    JSONObject()
                                        .put("minClearancePx", minClearancePx)
                                        .put("requiredClearancePx", requiredClearancePx)
                                        .put(
                                            "fontScale",
                                            expectedView.resources.configuration.fontScale
                                                .toDouble(),
                                        ).put("threePointsChecked", preview.points.size),
                                )
                            } catch (failure: Throwable) {
                                result.completeExceptionally(failure)
                            }
                        }
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
                result.await()
            } finally {
                result.cancel()
            }
        }

    private suspend fun freshTapAndCheck(
        preview: CourseMapPreview,
        index: Int,
        baseline: NativeReadback,
    ) {
        val token = beginNativeTap(preview, index, baseline.view, "fresh_after_boundary")
        delay(40)
        finishNativeTap(token, stale = false, boundary = "fresh_after_boundary")
        val selected = awaitSelection(preview, index, baseline.view)
        assertTrue("새 owner의 fresh 번호 탭도 camera를 바꾸면 안 됩니다", sameCamera(baseline.camera, selected.camera))
        assertSelectedDetails(preview.points[index].title)
    }

    private suspend fun assertOldUpRejected(
        prepared: PreparedPractice,
        preview: CourseMapPreview,
        baseline: NativeReadback,
        boundary: String,
        checks: JSONArray,
    ) {
        // Observe past the platform's single-tap confirmation delay without assuming a GPU frame.
        // Lifecycle duration can also exceed tap-time policy; isolated ownership controls are separate.
        compose.waitForIdle()
        assertNoSelectionDetails()
        delay(ViewConfiguration.getDoubleTapTimeout().toLong() + 100L)
        val after = assertInitial(prepared, preview)
        assertTrue("옛 UP 뒤에도 같은 현재 View여야 합니다", after.view === baseline.view)
        assertTrue("옛 UP는 새 owner의 camera나 선택을 바꾸면 안 됩니다", sameCamera(baseline.camera, after.camera))
        checks.put(JSONObject().put("boundary", boundary).put("oldUpRejected", true))
    }

    private fun activateSemantics(tag: String) {
        requireForeground()
        bringIntoView(tag)
        val action =
            checkNotNull(
                nodes(tag)
                    .single()
                    .config
                    .getOrNull(SemanticsActions.OnClick)
                    ?.action,
            )
        compose.runOnIdle { action.invoke() }
        compose.waitForIdle()
    }

    private suspend fun assertInitial(
        prepared: PreparedPractice,
        preview: CourseMapPreview,
    ): NativeReadback {
        waitFor("map_ready")
        assertText("map_course_title", prepared.course.title)
        assertText("map_preview_title", preview.title)
        assertText("map_demo_badge", text(MapR.string.map_demo_badge))
        assertText("map_point_selection_instruction", text(MapR.string.map_point_selection_instruction))
        for ((index, point) in preview.points.withIndex()) assertChoice(index, point.title, selected = false)
        assertTrue("네 번째 선택을 만들어서는 안 됩니다", nodes("map_point_3").isEmpty())
        assertNoSelectionDetails()
        bringIntoView("map_view")
        return awaitNative(preview, null, purpose = "initial_unselected_owner")
    }

    private suspend fun awaitSelection(
        preview: CourseMapPreview,
        index: Int,
        expectedView: MapView,
        purpose: String = "after_native_tap",
    ): NativeReadback {
        val point = preview.points[index]
        val readback = awaitNative(preview, point.id, expectedView, purpose)
        compose.waitUntil(20_000) {
            nodes("map_selected_point_title").singleOrNull()?.let { point.title in nodeTexts(it) } == true &&
                nodes("map_selection_pending").isEmpty() &&
                preview.points.indices.all { choice ->
                    nodes("map_point_$choice").singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == (choice == index)
                }
        }
        assertChoice(index, point.title, selected = true)
        return readback
    }

    private fun assertChoice(
        index: Int,
        title: String,
        selected: Boolean,
    ) {
        val tag = "map_point_$index"
        assertText(tag, "${index + 1}. $title")
        val node = nodes(tag).single()
        assertTrue("실제 선택 표시가 native ACK와 같은 지점을 가리켜야 합니다", node.config.getOrNull(SemanticsProperties.Selected) == selected)
        assertTrue("주차/UX 허용의 준비된 선택 버튼은 도달 가능해야 합니다", !node.config.contains(SemanticsProperties.Disabled))
    }

    private fun assertSelectedDetails(title: String) {
        waitFor("map_selected_point")
        assertText("map_selected_point_title", title)
        assertPanelText("map_selected_point", text(MapR.string.map_selected_point))
        assertPanelText("map_selected_point", text(MapR.string.map_selected_point_scope))
        assertTrue("완료 설명과 ACK 대기 문구가 동시에 남으면 안 됩니다", nodes("map_selection_pending").isEmpty())
    }

    private fun assertNoSelectionDetails() {
        listOf("map_selected_point", "map_selected_point_title", "map_selection_pending")
            .forEach { assertTrue("새 owner나 잠금에는 이전 선택/설명을 남기면 안 됩니다", nodes(it).isEmpty()) }
    }

    /** Projection and all JNI access stay on Main; only plain window coordinates leave the callback. */
    private suspend fun beginNativeTap(
        preview: CourseMapPreview,
        index: Int,
        expectedView: MapView,
        boundary: String,
    ): TapToken {
        requireForeground()
        bringIntoView("map_view")
        compose.waitForIdle()
        val result = CompletableDeferred<TapToken>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                assertTrue("탭은 살아 있는 현재 native View에만 보냅니다", expectedView.isAttachedToWindow && currentMapViewOnMain() === expectedView)
                val visible = Rect()
                assertTrue(
                    "지도 전체가 실제로 보이는 상태에서 번호를 누릅니다",
                    expectedView.getGlobalVisibleRect(visible) && visible.width() >= expectedView.width - 1 &&
                        visible.height() >= expectedView.height - 1,
                )
                expectedView.getMapAsync { map ->
                    try {
                        assertTrue("projection은 UI 스레드여야 합니다", Looper.myLooper() == Looper.getMainLooper())
                        assertTrue(
                            "projection 직전에도 현재 부착 View여야 합니다",
                            expectedView.isAttachedToWindow && currentMapViewOnMain() === expectedView,
                        )
                        val point = preview.points[index]
                        val local = map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                        assertTrue(
                            "현재 고정 지점의 실제 projection이 지도 내부여야 합니다",
                            local.x.isFinite() && local.y.isFinite() && local.x > 1f && local.y > 1f &&
                                local.x < expectedView.width - 1 && local.y < expectedView.height - 1,
                        )
                        val inWindow = IntArray(2)
                        expectedView.getLocationInWindow(inWindow)
                        result.complete(
                            TapToken(expectedView, SystemClock.uptimeMillis(), inWindow[0] + local.x, inWindow[1] + local.y, index),
                        )
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        val token = withTimeout(5_000) { result.await() }
        check(pendingTouch.compareAndSet(null, token)) { "동시에 다른 pointer stream을 만들지 않습니다" }
        val consumed = dispatchNativeEvent(token, MotionEvent.ACTION_DOWN, requireOriginalView = true)
        inputEvents.put(
            JSONObject()
                .put("boundary", boundary)
                .put("index", index)
                .put("action", "DOWN")
                .put("dispatchConsumed", consumed),
        )
        assertTrue("실제 Activity 경로가 번호 DOWN을 받아야 합니다", consumed)
        return token
    }

    private suspend fun finishNativeTap(
        token: TapToken,
        stale: Boolean,
        boundary: String,
    ) {
        check(pendingTouch.get() === token) { "보관한 동일 pointer stream의 UP만 전달합니다" }
        val elapsed = SystemClock.uptimeMillis() - token.downTime
        var delivered = false
        try {
            val consumed = dispatchNativeEvent(token, MotionEvent.ACTION_UP, requireOriginalView = !stale)
            delivered = true
            inputEvents.put(
                JSONObject()
                    .put("boundary", boundary)
                    .put("index", token.index)
                    .put("action", "UP")
                    .put("staleOwner", stale)
                    .put("dispatchConsumed", consumed)
                    .put("heldDurationMs", elapsed)
                    .put("withinProductTapDuration", elapsed >= 0 && elapsed < ViewConfiguration.getLongPressTimeout())
                    .put("withinLongPressTimeout", elapsed <= ViewConfiguration.getLongPressTimeout()),
            )
            if (!stale) assertTrue("fresh 번호 UP도 실제 Activity 경로로 전달돼야 합니다", consumed)
        } finally {
            if (delivered) pendingTouch.compareAndSet(token, null)
        }
        compose.waitForIdle()
    }

    private suspend fun cancelPendingTouch() {
        val token = pendingTouch.getAndSet(null) ?: return
        dispatchNativeEvent(token, MotionEvent.ACTION_CANCEL, requireOriginalView = false)
    }

    /** Full Activity/Window/Compose/AndroidView dispatch, never a direct onTouchEvent or future guard API. */
    private suspend fun dispatchNativeEvent(
        token: TapToken,
        action: Int,
        requireOriginalView: Boolean,
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                if (requireOriginalView) {
                    assertTrue("fresh 입력은 원래 현재 View에만 보냅니다", token.view.isAttachedToWindow && currentMapViewOnMain() === token.view)
                }
                // Stale UP/CANCEL deliberately targets the current Activity using the original
                // window coordinates, without querying or invoking the detached old native View.
                val coordinates =
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = token.windowX
                            y = token.windowY
                            pressure = 1f
                            size = 1f
                        },
                    )
                val properties =
                    arrayOf(
                        MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = MotionEvent.TOOL_TYPE_FINGER
                        },
                    )
                val event =
                    MotionEvent.obtain(
                        token.downTime,
                        SystemClock.uptimeMillis(),
                        action,
                        1,
                        properties,
                        coordinates,
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        0,
                    )
                try {
                    result.complete(compose.activity.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return withTimeout(5_000) { result.await() }
    }

    /** Only immutable values and the Java View identity escape; every JNI read stays on Main. */
    private suspend fun awaitNative(
        preview: CourseMapPreview,
        selectedId: String?,
        expectedView: MapView? = null,
        purpose: String,
    ): NativeReadback =
        withTimeout(30_000) {
            val readbackStarted = android.os.SystemClock.elapsedRealtime()
            val call = nativeCallSequence.incrementAndGet()
            var iteration = 0
            var found: NativeReadback? = null
            while (found == null) {
                // Main-thread JNI delivery alone does not advance Compose's auto-advance test clock.
                // Drain pending composition/AndroidView updates before the strict native readback.
                compose.waitForIdle()
                assertTrue("Compose idle도 native30초 한도에 포함합니다", android.os.SystemClock.elapsedRealtime() - readbackStarted <= 30_000)
                val diagnostic =
                    JSONObject()
                        .put("call", call)
                        .put("iteration", ++iteration)
                        .put("purpose", purpose)
                        .put("expectedSelectionPresent", selectedId != null)
                        .put("expectedPointIndex", selectedId?.let { id -> preview.points.indexOfFirst { it.id == id } } ?: JSONObject.NULL)
                        .put("viewIdentityPinned", expectedView != null)

                fun publish(stage: String) {
                    diagnostic.put("stage", stage)
                    lastNativeSnapshot.set(diagnostic.toString())
                }
                publish("before_main_view_query")
                val result = CompletableDeferred<NativeReadback?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    try {
                        val view = currentMapViewOnMain()
                        diagnostic
                            .put("currentViewPresent", view != null)
                            .put("currentViewPositiveSize", view != null && view.width > 0 && view.height > 0)
                        publish("main_view_queried")
                        if (view == null || view.width <= 0 || view.height <= 0) {
                            result.complete(null)
                        } else {
                            diagnostic.put(
                                "viewMatchesPinnedIdentity",
                                if (expectedView == null) JSONObject.NULL else view === expectedView,
                            )
                            publish("before_map_callback")
                            if (expectedView != null) assertTrue("선택은 같은 부착 native View에 전달돼야 합니다", view === expectedView)
                            view.getMapAsync { map ->
                                try {
                                    val mainThread = Looper.myLooper() == Looper.getMainLooper()
                                    val attached = view.isAttachedToWindow
                                    val current = currentMapViewOnMain() === view
                                    diagnostic
                                        .put("callbackOnMain", mainThread)
                                        .put("callbackViewAttached", attached)
                                        .put("callbackViewIsCurrent", current)
                                    publish("map_callback_received")
                                    assertTrue("native readback은 UI 스레드에서만 합니다", mainThread)
                                    if (!attached || !current) {
                                        result.complete(null)
                                    } else {
                                        val style = map.style
                                        val source = style?.getSource(SOURCE_ID) as? GeoJsonSource
                                        // In 13.5 getLayer prefers Java layers added during creation, while
                                        // getLayerAs always asks NativeMap.getLayer, matching the product setter.
                                        // The cache-preferring wrapper is diagnostic only, never the selection oracle.
                                        val circles = style?.getLayerAs<CircleLayer>(CIRCLES_ID)
                                        val numbers = style?.getLayerAs<SymbolLayer>(NUMBERS_ID)
                                        val selected = style?.getLayerAs<CircleLayer>(SELECTION_ID)
                                        val legacySelection = style?.getLayer(SELECTION_ID) as? CircleLayer
                                        val expectedFilter =
                                            if (selectedId == null) {
                                                Expression.literal(false)
                                            } else {
                                                Expression.eq(Expression.get("point_id"), Expression.literal(selectedId))
                                            }
                                        val loaded = style?.isFullyLoaded == true
                                        val uriMatches = style?.uri == expectedStyle()
                                        val circleSourceMatches = circles?.sourceId == SOURCE_ID
                                        val numberSourceMatches = numbers?.sourceId == SOURCE_ID
                                        val selectionSourceMatches = selected?.sourceId == SOURCE_ID
                                        val circleVisible = circles != null && circles.visibility.value != "none"
                                        val numberVisible = numbers != null && numbers.visibility.value != "none"
                                        val selectionVisible = selected != null && selected.visibility.value != "none"
                                        val actualFilter = selected?.filter?.toString()
                                        val legacyFilterRead = runCatching { legacySelection?.filter?.toString() }
                                        val legacyFilter = legacyFilterRead.getOrNull()
                                        val expectedFilterText = expectedFilter.toString()
                                        val filterMatches = actualFilter == expectedFilterText
                                        val actualCanonical = sanitizedFilter(actualFilter, preview)
                                        val expectedCanonical = sanitizedFilter(expectedFilterText, preview)
                                        diagnostic
                                            .put("layerGetterPath", "typed_getLayerAs_current_native")
                                            .put("sourceGetterPath", "getSource_cache_preferring_wrapper_with_native_query")
                                            .put("legacyCachePreferringSelectionPresent", legacySelection != null)
                                            .put(
                                                "legacyAndNativeSelectionSameJavaObject",
                                                legacySelection != null && legacySelection === selected,
                                            ).put("legacyFilterReadAvailable", legacyFilter != null)
                                            .put(
                                                "legacyFilterReadFailureClass",
                                                legacyFilterRead.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL,
                                            ).put("legacyFilterCanonicalRedacted", sanitizedFilter(legacyFilter, preview))
                                            .put(
                                                "legacyFilterMatchesExpected",
                                                legacyFilter?.let { it == expectedFilterText } ?: JSONObject.NULL,
                                            ).put(
                                                "legacyAndNativeFilterRawEqual",
                                                if (legacyFilter == null || actualFilter == null) {
                                                    JSONObject.NULL
                                                } else {
                                                    legacyFilter == actualFilter
                                                },
                                            )
                                        diagnostic
                                            .put("stylePresent", style != null)
                                            .put("styleFullyLoaded", loaded)
                                            .put("styleUriMatchesExpected", uriMatches)
                                            .put("sourcePresent", source != null)
                                            .put("circleLayerPresent", circles != null)
                                            .put("numberLayerPresent", numbers != null)
                                            .put("selectionLayerPresent", selected != null)
                                            .put("circleSourceMatches", circleSourceMatches)
                                            .put("numberSourceMatches", numberSourceMatches)
                                            .put("selectionSourceMatches", selectionSourceMatches)
                                            .put("circleLayerVisible", circleVisible)
                                            .put("numberLayerVisible", numberVisible)
                                            .put("selectionLayerVisible", selectionVisible)
                                            .put("filterReadAvailable", actualFilter != null)
                                            .put("filterRawStringEqual", filterMatches)
                                            .put("filterActualCanonicalRedacted", actualCanonical)
                                            .put("filterExpectedCanonicalRedacted", expectedCanonical)
                                            .put(
                                                "filterSanitizedCanonicalEqual",
                                                actualCanonical.toString() == expectedCanonical.toString(),
                                            )
                                        var valid =
                                            style != null && loaded && uriMatches && source != null &&
                                                circles != null && numbers != null && selected != null &&
                                                circleSourceMatches && numberSourceMatches && selectionSourceMatches &&
                                                circleVisible && numberVisible && selectionVisible && filterMatches
                                        diagnostic.put("predicateAfterLayers", valid)
                                        publish("layer_filter_predicate_complete")
                                        val features = source?.querySourceFeatures(null).orEmpty()
                                        val foundIds = features.map { it.getStringProperty("point_id") }.toSet()
                                        val expectedIds = preview.points.map { it.id }.toSet()
                                        val idsMatch = foundIds == expectedIds
                                        diagnostic
                                            .put("sourceFeatureQueryCompleted", source != null)
                                            .put("sourceFeatureCount", if (source == null) JSONObject.NULL else features.size)
                                            .put("distinctFeatureIdCount", if (source == null) JSONObject.NULL else foundIds.size)
                                            .put("expectedDistinctPointCount", expectedIds.size)
                                            .put("featureIdsMatch", idsMatch)
                                        valid = valid && idsMatch
                                        val featureChecks = JSONArray()
                                        diagnostic.put("featureChecks", featureChecks).put("predicateAfterFeatureIds", valid)
                                        publish("feature_ids_complete")
                                        for ((ordinal, feature) in features.withIndex()) {
                                            val index = preview.points.indexOfFirst { it.id == feature.getStringProperty("point_id") }
                                            val check = JSONObject().put("ordinal", ordinal).put("idRecognized", index >= 0)
                                            featureChecks.put(check)
                                            publish("before_feature_metadata")
                                            if (index < 0) {
                                                valid = false
                                                break
                                            }
                                            val point = preview.points[index]
                                            val geometry = feature.geometry() as? Point
                                            val titleMatches = feature.getStringProperty("title") == point.title
                                            val previewMatches = feature.getStringProperty("preview_id") == preview.id
                                            val versionMatches =
                                                feature.getNumberProperty("preview_version").toDouble() == preview.version.toDouble()
                                            val orderMatches = feature.getNumberProperty("order").toDouble() == (index + 1).toDouble()
                                            check
                                                .put("expectedPointIndex", index)
                                                .put("geometryIsPoint", geometry != null)
                                                .put("titleMatches", titleMatches)
                                                .put("previewIdMatches", previewMatches)
                                                .put("previewVersionMatches", versionMatches)
                                                .put("orderMatches", orderMatches)
                                            valid = valid && geometry != null && titleMatches && previewMatches &&
                                                versionMatches && orderMatches
                                            publish("feature_metadata_complete")
                                            if (geometry != null) {
                                                val actual =
                                                    map.projection.toScreenLocation(
                                                        LatLng(geometry.latitude(), geometry.longitude()),
                                                    )
                                                val expected = map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                                                val xMatches = abs(actual.x - expected.x) <= 1f
                                                val yMatches = abs(actual.y - expected.y) <= 1f
                                                check.put("projectionXWithinOnePixel", xMatches).put("projectionYWithinOnePixel", yMatches)
                                                valid = valid && xMatches && yMatches
                                            }
                                            check.put("accumulatedPredicate", valid)
                                            publish("feature_projection_complete")
                                        }
                                        val camera = map.cameraPosition
                                        val target = camera.target
                                        diagnostic
                                            .put("cameraTargetPresent", target != null)
                                            .put("nativePredicateSatisfied", valid)
                                            .put("readbackAccepted", valid && target != null)
                                        publish("native_predicate_complete")
                                        if (valid && target != null) {
                                            val visible = Rect()
                                            val intersects = view.getGlobalVisibleRect(visible) && !visible.isEmpty
                                            val fullyVisible =
                                                intersects && visible.width() >= view.width - 1 &&
                                                    visible.height() >= view.height - 1
                                            result.complete(
                                                NativeReadback(
                                                    view,
                                                    CameraSnapshot(
                                                        target.latitude,
                                                        target.longitude,
                                                        camera.zoom,
                                                        camera.bearing,
                                                        camera.tilt,
                                                    ),
                                                    intersects,
                                                    fullyVisible,
                                                    view.hasFocus(),
                                                ),
                                            )
                                        } else {
                                            result.complete(null)
                                        }
                                    }
                                } catch (failure: Throwable) {
                                    diagnostic.put("failureClass", failure.javaClass.simpleName)
                                    publish("map_callback_exception")
                                    result.completeExceptionally(failure)
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        diagnostic.put("failureClass", failure.javaClass.simpleName)
                        publish("main_view_query_exception")
                        result.completeExceptionally(failure)
                    }
                }
                found = withTimeout(5_000) { result.await() }
                assertTrue("응답 승인도 native30초 한도 안이어야 합니다", android.os.SystemClock.elapsedRealtime() - readbackStarted <= 30_000)
                lastNativeSnapshot.get()?.let { snapshot ->
                    if (nativeLoopHistory.size >= 12) nativeLoopHistory.removeFirst()
                    nativeLoopHistory.addLast(snapshot)
                }
                if (found == null) delay(100)
            }
            checkNotNull(found)
        }

    /** Keep filter grammar, booleans and logical point indexes; redact every data-bearing string/number. */
    private fun sanitizedFilter(
        value: String?,
        preview: CourseMapPreview,
    ): Any {
        if (value == null) return JSONObject.NULL

        fun redact(input: Any?): Any =
            when (input) {
                is JSONArray -> JSONArray().apply { for (index in 0 until input.length()) put(redact(input.get(index))) }
                is JSONObject -> JSONObject().put("unexpectedObjectShape", true)
                is Boolean -> input
                is Number -> "<number>"
                is String ->
                    when (input) {
                        "==", "get", "point_id", "literal", "any", "all", "!", "in", "has", "match", "case" -> input
                        else -> {
                            val index = preview.points.indexOfFirst { it.id == input }
                            if (index >= 0) "<point-index-$index>" else "<redacted-string>"
                        }
                    }
                else -> JSONObject.NULL
            }
        return try {
            redact(JSONTokener(value).nextValue())
        } catch (_: Exception) {
            JSONObject().put("canonicalParseFailed", true)
        }
    }

    /** Full pixels remain private; JSON contains no visible text, source identity, times or location. */
    private fun preserveFailure(
        directory: File,
        original: Throwable,
    ) {
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val details =
                JSONObject()
                    .put("schema", 1)
                    .put("inputEvents", inputEvents)
                    .put("pendingTouchAtFailure", pendingTouch.get() != null)
                    .put("beforeCleanup", true)
                    .put("foregroundInspectionComplete", false)
                    .put("foregroundAvailable", JSONObject.NULL)
                    .put("foregroundPackageMatches", JSONObject.NULL)
                    .put("screenshotCaptured", false)
                    .put("semanticsInspectionComplete", false)
                    .put("lastNativeReadback", lastNativeSnapshot.get()?.let(::JSONObject) ?: JSONObject.NULL)
                    .put("recentNativeLoopSnapshots", JSONArray(nativeLoopHistory.map(::JSONObject)))
            try {
                val foreground =
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.packageName
                        ?.toString()
                details
                    .put("foregroundAvailable", foreground != null)
                    .put(
                        "foregroundPackageMatches",
                        foreground?.let { it == instrumentation.targetContext.packageName } ?: JSONObject.NULL,
                    ).put("foregroundInspectionComplete", true)
            } catch (diagnostic: Throwable) {
                if (diagnostic !== original) original.addSuppressed(diagnostic)
            }
            var screenshot: Bitmap? = null
            try {
                val captured = instrumentation.uiAutomation.takeScreenshot()
                screenshot = captured
                if (captured != null) {
                    val written =
                        File(directory, "failure-full.png").outputStream().use {
                            captured.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    details.put("screenshotCaptured", written)
                    check(written) { "실제 실패 화면을 비공개로 저장하지 못했습니다" }
                }
            } catch (diagnostic: Throwable) {
                if (diagnostic !== original) original.addSuppressed(diagnostic)
            } finally {
                screenshot?.recycle()
            }
            val counts = JSONObject()
            details.put("tagCounts", counts)
            try {
                listOf(
                    "home_map",
                    "map_ready",
                    "map_container",
                    "map_course_title",
                    "map_view",
                    "map_loading",
                    "map_locked",
                    "map_error",
                    "map_point_0",
                    "map_point_1",
                    "map_point_2",
                    "map_selected_point",
                    "map_selected_point_title",
                    "map_selection_pending",
                    "map_point_selection_instruction",
                ).forEach { counts.put(it, nodes(it).size) }
                details
                    .put(
                        "semanticsRootCount",
                        compose
                            .onAllNodes(isRoot(), useUnmergedTree = true)
                            .fetchSemanticsNodes(atLeastOneRootRequired = false)
                            .size,
                    ).put("semanticsInspectionComplete", true)
            } catch (diagnostic: Throwable) {
                if (diagnostic !== original) original.addSuppressed(diagnostic)
            }
            File(directory, "failure-anonymous.json").writeText(details.toString(2))
        } catch (diagnostic: Throwable) {
            if (diagnostic !== original) original.addSuppressed(diagnostic)
        }
    }

    private fun writeTapEvidence(
        directory: File,
        evidence: JSONObject,
    ) {
        File(directory, "tap-conditions.json").writeText(evidence.toString(2))
    }

    private fun expectedStyle(): String {
        val configuration =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.configuration
        return "https://tiles.openfreemap.org/styles/" +
            if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "liberty"
    }

    private fun currentMapViewOnMain(): MapView? =
        mapViews(compose.activity.window.decorView).singleOrNull { it.isAttachedToWindow && it.visibility == View.VISIBLE }

    private fun mapViews(view: View): List<MapView> =
        when (view) {
            is MapView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { mapViews(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun sameCamera(
        a: CameraSnapshot,
        b: CameraSnapshot,
    ): Boolean =
        abs(a.latitude - b.latitude) <= 0.000000001 && abs(a.longitude - b.longitude) <= 0.000000001 &&
            abs(a.zoom - b.zoom) <= 0.000000001 && abs(a.bearing - b.bearing) <= 0.000000001 && abs(a.tilt - b.tilt) <= 0.000000001

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(20_000) {
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
        assertTrue("원래 준비/모든 세션/관찰/메모의 domain 필드와 순서를 보존해야 합니다", snapshot(graph) == before)
        assertTrue("읽기 검사로 활성 연습이 생기면 안 됩니다", before.sessions.none { it.isActive })
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private fun verifyConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        val scale = args.getString("expectedFontScale")?.let { checkNotNull(it.toFloatOrNull()) { "글꼴 선언이 잘못됐습니다" } } ?: 1f
        val dark = args.getString("expectedDark")?.let { checkNotNull(it.toBooleanStrictOrNull()) { "야간 선언이 잘못됐습니다" } } ?: false
        assertTrue("정의한 기본/확대 조건만 검사합니다", scale == 1f || scale == 1.3f)
        val config =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.configuration
        assertTrue("실제 글꼴이 선언과 같아야 합니다", abs(config.fontScale - scale) < 0.001f)
        assertTrue("실제 테마가 선언과 같아야 합니다", (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark)
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        bringIntoView(tag)
        val matching =
            descendants(nodes(tag).single()).filter {
                it.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { value -> value.text == expected }
            }
        assertTrue("실제 표시가 현재 고정 원문과 같아야 합니다", matching.isNotEmpty())
        matching.forEach { assertLayout(it, expected) }
    }

    private fun assertPanelText(
        tag: String,
        expected: String,
    ) {
        waitFor(tag)
        val node =
            descendants(nodes(tag).single()).single {
                it.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { value -> value.text == expected }
            }
        val matcher = SemanticsMatcher("선택 설명의 익명 Text 노드") { it.id == node.id }
        if (!fullyVisible(node)) compose.onNode(matcher, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        val current = compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("선택 설명의 전체 문장이 화면에 보여야 합니다", fullyVisible(current))
        assertLayout(current, expected)
    }

    private fun assertLayout(
        node: SemanticsNode,
        expected: String,
    ) {
        var valid = false
        compose.runOnIdle {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            val matching = layouts.filter { it.layoutInput.text.text == expected }
            valid = matching.isNotEmpty() && matching.all { textGeometryFits(it, expected) }
        }
        assertTrue("실제 글자/줄 전체가 잘리거나 말줄임되지 않아야 합니다", valid)
    }

    private fun textGeometryFits(
        layout: TextLayoutResult,
        expected: String,
    ): Boolean {
        if (layout.layoutInput.text.text != expected || expected.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 ||
            layout.lineCount <= 0 || layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }
        var covered = 0
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (layout.getLineStart(line) != covered || end < covered || end > expected.length || layout.isLineEllipsized(line)) {
                return false
            }
            if (!inside(
                    layout.getLineLeft(line),
                    layout.getLineTop(line),
                    layout.getLineRight(line),
                    layout.getLineBottom(line),
                    layout.size,
                )
            ) {
                return false
            }
            covered = end
        }
        if (covered != expected.length) return false
        for (index in expected.indices) {
            if (expected[index].isWhitespace()) continue
            val box = layout.getBoundingBox(index)
            if (!inside(box.left, box.top, box.right, box.bottom, layout.size)) return false
        }
        return true
    }

    private fun inside(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        size: IntSize,
    ): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && left <= right && top <= bottom &&
            left >= -1f && top >= -1f && right <= size.width + 1f && bottom <= size.height + 1f

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun waitFor(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }

    private fun touch(tag: String) {
        requireForeground()
        bringIntoView(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun bringIntoView(tag: String) {
        waitFor(tag)
        if (!fullyVisible(nodes(tag).single())) compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitUntil(15_000) { nodes(tag).singleOrNull()?.let(::fullyVisible) == true }
    }

    private fun fullyVisible(node: SemanticsNode): Boolean {
        val available = appViewport()
        val bounds = node.boundsInWindow
        val origin = node.positionOnScreen - node.positionInWindow
        return node.size.width > 0 && node.size.height > 0 &&
            bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
            bounds.left + origin.x >= available.left - 1 && bounds.top + origin.y >= available.top - 1 &&
            bounds.right + origin.x <= available.right + 1 && bounds.bottom + origin.y <= available.bottom + 1
    }

    private fun appViewport(): Rect {
        val available = Rect()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.decorView
                .getWindowVisibleDisplayFrame(available)
        }
        assertTrue("실제 앱 가용 영역이 있어야 합니다", !available.isEmpty)
        return available
    }

    private fun capture(
        directory: File,
        name: String,
        tag: String,
    ) {
        bringIntoView(tag)
        compose.waitForIdle()
        requireForeground()
        val node = nodes(tag).single()
        val bounds = node.boundsInWindow
        val origin = node.positionOnScreen - node.positionInWindow
        val crop =
            Rect(
                floor((bounds.left + origin.x).toDouble()).toInt(),
                floor((bounds.top + origin.y).toDouble()).toInt(),
                ceil((bounds.right + origin.x).toDouble()).toInt(),
                ceil((bounds.bottom + origin.y).toDouble()).toInt(),
            )
        assertTrue("실제 앱 영역 안의 완성된 UI만 캡처합니다", appViewport().contains(crop))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(500, 5_000)
        assertTrue(
            "캡처 직전에도 실제 앱이 전면이어야 합니다",
            automation.rootInActiveWindow?.packageName?.toString() ==
                InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val bitmap = checkNotNull(automation.takeScreenshot()) { "실제 앱 화면이 필요합니다" }
        try {
            assertTrue(
                "원본 실제 픽셀 안의 범위만 잘라야 합니다",
                crop.left >= 0 && crop.top >= 0 && crop.right <= bitmap.width && crop.bottom <= bitmap.height,
            )
            val cut = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
            try {
                File(directory, "$name.png").outputStream().use { check(cut.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                cut.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun requireForeground() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val expected = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expected }
        assertTrue("실제 Main 앱 전면에서만 동작/캡처를 확인합니다", automation.rootInActiveWindow?.packageName?.toString() == expected)
    }

    private fun text(resource: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)

    private fun phase(value: String) =
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$value\n") })

    private data class TapToken(
        val view: MapView,
        val downTime: Long,
        val windowX: Float,
        val windowY: Float,
        val index: Int,
    )

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
    )

    private data class NativeReadback(
        val view: MapView,
        val camera: CameraSnapshot,
        val physicallyVisible: Boolean,
        val fullyPhysicallyVisible: Boolean,
        val viewHasFocus: Boolean,
    )

    private data class CameraSnapshot(
        val latitude: Double,
        val longitude: Double,
        val zoom: Double,
        val bearing: Double,
        val tilt: Double,
    )

    private companion object {
        const val SOURCE_ID = "hangil-course-preview-points"
        const val CIRCLES_ID = "hangil-course-preview-circles"
        const val NUMBERS_ID = "hangil-course-preview-numbers"
        const val SELECTION_ID = "hangil-course-preview-selection"
    }
}
