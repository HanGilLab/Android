package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
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
 * Explicit actual Main/SDK/native style check of the currently prepared fixed three-point preview.
 * No preparation, session, observation, memo, permission, camera or setting is written by this test.
 * The external owner preflights the actual DDS bridge/P/UX and preserves raw SQLite/settings.
 * Domain snapshots below compare all fields/order, not raw SQLite types or sequences. Fixed OSM
 * center points are not GPS, road sections or destinations. Style filter/ACK is not a GPU/frame
 * completion or timing claim. Private real UI crops permit separate rendered-highlight review.
 */
@RunWith(AndroidJUnit4::class)
class MapPointSelectionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val nativeCallSequence = AtomicInteger(0)
    private val lastNativeSnapshot = AtomicReference<String?>(null)
    private val nativeLoopHistory = ArrayDeque<String>()

    @Test fun fixedPointChoicesMatchNativeHighlightAndDiscardSelectionAtEveryOwnerBoundary() {
        assumeTrue(
            "실제 SDK와 지도/원본 보존을 준비한 명시 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualMapPointSelection") == "true",
        )
        verifyConfiguration()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("전용 debug 앱에서만 원래 그래프를 읽습니다", context.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("debug 빌드가 필요합니다", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val directory = File(context.cacheDir, "screenshots/map-point-selection-${UUID.randomUUID()}")
        assertTrue("이 실행의 새 비공개 캡처 폴더만 만듭니다", directory.mkdirs())
        val conditions = JSONArray()
        val evidence = JSONObject().put("schema", 1).put("conditions", conditions).put("scenarioCompleted", false)
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
                var previousId: String? = null
                for ((index, point) in preview.points.withIndex()) {
                    if (index > 0) {
                        scrollPageForPointChoice(index)
                    } else {
                        bringIntoView("map_point_$index")
                    }
                    val beforeClick = awaitNative(preview, previousId, initial.view, "before_list_click")
                    assertTrue("목록 스크롤이 camera를 바꾸면 안 됩니다", sameCamera(initial.camera, beforeClick.camera))
                    val condition =
                        JSONObject()
                            .put("index", index)
                            .put("nativeViewIntersectsViewport", beforeClick.physicallyVisible)
                            .put("nativeViewFullyVisible", beforeClick.fullyPhysicallyVisible)
                            .put("nativeViewHasFocus", beforeClick.viewHasFocus)
                            .put("nativeFilterConfirmed", JSONObject.NULL)
                            .put("cameraUnchanged", JSONObject.NULL)
                            .put("displayTextConfirmed", JSONObject.NULL)
                            .put("selectionVerified", false)
                    conditions.put(condition)
                    writeConditions(directory, evidence)
                    assertChoice(index, point.title, selected = false)
                    touch("map_point_$index")
                    val selected = awaitSelection(preview, index, initial.view)
                    val unchanged = sameCamera(initial.camera, selected.camera)
                    condition.put("nativeFilterConfirmed", true).put("cameraUnchanged", unchanged)
                    writeConditions(directory, evidence)
                    assertTrue("지점 강조는 camera를 이동하면 안 됩니다", unchanged)
                    assertSelectedDetails(point.title)
                    condition.put("displayTextConfirmed", true)
                    assertPreserved(graph, before)
                    condition.put("selectionVerified", true)
                    writeConditions(directory, evidence)
                    capture(directory, "point-$index-choice", "map_point_$index")
                    capture(directory, "point-$index-details", "map_selected_point")
                    capture(directory, "point-$index-map", "map_view")
                    assertTrue(
                        "캡처를 위한 스크롤도 camera를 바꾸면 안 됩니다",
                        sameCamera(initial.camera, awaitNative(preview, point.id, initial.view, "after_capture_scroll").camera),
                    )
                    previousId = point.id
                }
                compose.activityRule.scenario.recreate()
                val recreated = assertInitial(prepared, preview)
                touch("map_point_0")
                awaitSelection(preview, 0, recreated.view)
                touch("map_close")
                waitFor("home_map")
                assertNoSelectionDetails()
                touch("home_map")
                val reentered = assertInitial(prepared, preview)
                touch("map_point_1")
                awaitSelection(preview, 1, reentered.view)
                bringIntoView("map_point_2")
                val beforeStopAction =
                    checkNotNull(
                        nodes("map_point_2")
                            .single()
                            .config
                            .getOrNull(SemanticsActions.OnClick)
                            ?.action,
                    )
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                val resumed = assertInitial(prepared, preview)
                // GREEN ownership check only: the pre-key product was not executed by this test.
                compose.runOnIdle { beforeStopAction.invoke() }
                compose.waitForIdle()
                assertNoSelectionDetails()
                preview.points.forEachIndexed { index, point -> assertChoice(index, point.title, selected = false) }
                awaitNative(preview, null, resumed.view, "after_captured_stop_action")
                touch("map_point_2")
                awaitSelection(preview, 2, resumed.view)
                assertPreserved(graph, before)
                phase("POINT_SELECTION_AWAIT_D")
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
                phase("POINT_SELECTION_AWAIT_P")
                awaitAllowed(graph)
                val returned = assertInitial(prepared, preview)
                touch("map_point_0")
                val finalSelection = awaitSelection(preview, 0, returned.view)
                assertTrue("P 복귀 후 새 선택도 camera를 바꾸면 안 됩니다", sameCamera(returned.camera, finalSelection.camera))
                assertSelectedDetails(preview.points[0].title)
                capture(directory, "returned-map", "map_view")
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
                        writeConditions(directory, evidence)
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
            writeConditions(directory, evidence)
            phase("POINT_SELECTION_COMPLETED")
        }
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
    ): NativeReadback {
        val point = preview.points[index]
        val readback = awaitNative(preview, point.id, expectedView, "after_list_click")
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

    /** Scroll the outer page via its real semantics; never drag the MapView or change camera. */
    private fun scrollPageForPointChoice(index: Int) {
        val point = nodes("map_point_$index").single()
        val scrolling =
            generateSequence(point.parent) { it.parent }.first {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                    it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            }
        compose.runOnIdle {
            val range = checkNotNull(scrolling.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
            val action = checkNotNull(scrolling.config.getOrNull(SemanticsActions.ScrollBy)?.action)
            val maximum = range.maxValue()
            val current = range.value()
            assertTrue("실제 페이지의 유한한 스크롤 범위를 확인해야 합니다", maximum.isFinite() && current.isFinite() && maximum >= current)
            assertTrue("실제 바깥 페이지를 끝까지 스크롤할 수 있어야 합니다", action.invoke(0f, maximum - current + 1f))
        }
        compose.waitForIdle()
        bringIntoView("map_point_$index")
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

    /** Anonymous observed conditions only: no source IDs, camera coordinates, text or device values. */
    private fun writeConditions(
        directory: File,
        evidence: JSONObject,
    ) {
        val values = evidence.getJSONArray("conditions")
        var offscreen = false
        var clipped = false
        var withoutFocus = false
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            if (!value.getBoolean("selectionVerified")) continue
            offscreen = offscreen || !value.getBoolean("nativeViewIntersectsViewport")
            clipped = clipped || value.getBoolean("nativeViewIntersectsViewport") && !value.getBoolean("nativeViewFullyVisible")
            withoutFocus = withoutFocus || !value.getBoolean("nativeViewHasFocus")
        }
        evidence
            .put("fullyOffscreenSelectionVerified", offscreen)
            .put("partiallyClippedSelectionVerified", clipped)
            .put("withoutNativeFocusSelectionVerified", withoutFocus)
            .put("offscreenCoverage", if (offscreen) "observed_and_verified" else "not_exercised_in_this_viewport")
        File(directory, "selection-conditions.json").writeText(evidence.toString(2))
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
