package io.github.hangillab.hangil

import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.LocalPracticeCatalog
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.map.MapLoadState
import io.github.hangillab.hangil.feature.map.MapRoute
import io.github.hangillab.hangil.feature.map.MapViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Explicit controlled inputs with a real public Route/VM/catalog and MapLibre13.5 native view.
 * State receipts and preparation are synthetic: no real VehicleGateway/Room graph is accessed.
 * Real public style/tile network, Activity.dispatchTouchEvent and SDK camera readback are used.
 * This proves neither real DDS ordering, GPS nor performance. No capture or app-domain write
 * occurs. Normal SDK style/tile cache behavior is retained, never cleared or substituted.
 */
@RunWith(AndroidJUnit4::class)
class MapInputBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun nativePanCannotUseOldDisplayedPAfterSilentLiveD() {
        assumeTrue(
            "합성 입력과 실제 native 지도 입력 경계의 명시적 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualMapInputBoundary") == "true",
        )
        val catalog = LocalPracticeCatalog()
        val course = catalog.courses.first { it.mapPreview != null }
        val preview = checkNotNull(course.mapPreview)
        val goal = catalog.goals.first { it.id in course.goalIds }
        val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, course.tasks.first().id)
        val repository = FixturePreparation(selection)
        val vehicleState = SilentLiveState(parked())
        val restrictionState = SilentLiveState(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val vehicle =
            object : VehicleGateway {
                override val state: StateFlow<VehicleState> = vehicleState
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions: StateFlow<DrivingRestrictions> = restrictionState
            }
        lateinit var model: MapViewModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val factory =
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        assertTrue("실제 Map ViewModel만 명시적으로 생성합니다", modelClass == MapViewModel::class.java)
                        @Suppress("UNCHECKED_CAST")
                        return MapViewModel(vehicle, restrictions, repository, catalog) as T
                    }
                }
            model = ViewModelProvider(compose.activity, factory)[MapViewModel::class.java]
        }
        val shown = mutableStateOf(true)
        var primaryFailure: Throwable? = null
        var ownedView: MapView? = null
        var initialCamera: CameraSnapshot? = null
        try {
            compose.setContent {
                HanGilTheme {
                    if (shown.value) MapRoute(onClose = {}, viewModel = model)
                }
            }
            runBlocking {
                compose.waitUntil(90_000) { model.uiState.value.load == MapLoadState.READY && nodes("map_ready").size == 1 }
                compose.onNodeWithTag("map_view", useUnmergedTree = true).performScrollTo()
                compose.waitForIdle()
                val initial = awaitPreview(preview)
                ownedView = initial.view
                initialCamera = initial.camera
                assertTrue("합성 P는 실제 VM 표시에서도 허용이어야 합니다", model.uiState.value.allowsDetailedInteraction)
                dispatchHorizontalPan(initial.view)
                val panned = awaitStableCamera(initial.view)
                assertTrue(
                    "양성 대조의 실제 P pan은 SDK camera 중심을 분명히 이동시켜야 합니다",
                    abs(initial.camera.latitude - panned.latitude) > 0.000001 ||
                        abs(initial.camera.longitude - panned.longitude) > 0.000001,
                )
                // Reset the positive control before the denied comparison; never fake the readback.
                restoreCamera(initial.view, initial.camera)
                val restored = awaitStableCamera(initial.view)
                assertTrue("거부 비교는 같은 실제 camera에서 시작해야 합니다", sameCamera(initial.camera, restored))
                awaitPreview(preview)
                val displayedP = model.uiState.value
                compose.runOnIdle {
                    vehicleState.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
                    assertTrue("무방출 D 뒤 옛 P UI가 남아야 경계를 재현합니다", displayedP == model.uiState.value)
                    assertTrue(
                        "fixture의 최신 값은 D여야 합니다",
                        vehicleState.value.gear == VehicleReading.Available(GearPosition.DRIVE),
                    )
                }
                compose.waitForIdle()
                assertTrue("옛 P의 실제 native View가 같은 identity로 남아 있어야 합니다", currentMapView() === initial.view)
                assertTrue("제스처 전에는 옛 Ready가 남아 있어야 합니다", nodes("map_ready").size == 1 && nodes("map_locked").isEmpty())
                dispatchHorizontalPan(initial.view)
                val deniedCamera = awaitStableCamera(initial.view)
                assertTrue("같은 실제 native View는 최신 D에서 수평 pan을 처리하면 안 됩니다", sameCamera(restored, deniedCamera))
                assertEquals("지도 입력은 fixture 준비를 저장하면 안 됩니다", 0, repository.writes.get())
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                instrumentation.runOnMainSync { vehicleState.setWithoutEmission(parked()) }
                val view = ownedView
                val camera = initialCamera
                if (view != null && camera != null && currentMapView() === view) {
                    runBlocking { restoreCamera(view, camera) }
                }
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
                compose.waitUntil(20_000) { repository.active.get() == 0 && currentMapView() == null }
                assertEquals("실제 앱 자료와 구분한 fixture 저장 호출은0이어야 합니다", 0, repository.writes.get())
                assertTrue("합성 준비 원본을 바꾸면 안 됩니다", selection == repository.values.value)
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private suspend fun dispatchHorizontalPan(view: MapView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for (index in 0..GESTURE_STEPS) {
            instrumentation.runOnMainSync {
                assertTrue("살아 있는 동일 native View로만 입력을 전달합니다", view.isAttachedToWindow && currentMapViewOnMain() === view)
                val inWindow = IntArray(2)
                view.getLocationInWindow(inWindow)
                val fraction = 0.38f + 0.24f * index / GESTURE_STEPS
                val coordinates =
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = inWindow[0] + view.width * fraction
                            y = inWindow[1] + view.height * 0.5f
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
                val action =
                    when (index) {
                        0 -> MotionEvent.ACTION_DOWN
                        GESTURE_STEPS -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                val event =
                    MotionEvent.obtain(
                        down,
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
                    // Full Activity/Window/Compose/AndroidView dispatch, never direct onTouchEvent.
                    compose.activity.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            if (index < GESTURE_STEPS) delay(30)
        }
    }

    private suspend fun awaitPreview(preview: CourseMapPreview): NativeReadback =
        withTimeout(90_000) {
            var value: NativeReadback? = null
            while (value == null) {
                value = nativePreviewReadback(preview)
                if (value == null) delay(150)
            }
            checkNotNull(value)
        }

    private suspend fun awaitStableCamera(view: MapView): CameraSnapshot =
        withTimeout(10_000) {
            var last = readCamera(view)
            var stable = 0
            while (stable < 3) {
                delay(150)
                val next = readCamera(view)
                stable = if (sameCamera(last, next)) stable + 1 else 0
                last = next
            }
            last
        }

    private suspend fun readCamera(view: MapView): CameraSnapshot {
        val result = CompletableDeferred<CameraSnapshot>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("camera는 살아 있는 native View의 UI 스레드에서만 읽습니다", view.isAttachedToWindow && currentMapViewOnMain() === view)
            view.getMapAsync { map ->
                try {
                    assertTrue("native camera callback은 UI 스레드여야 합니다", Looper.myLooper() == Looper.getMainLooper())
                    assertTrue("native camera query 전에 View가 살아 있어야 합니다", view.isAttachedToWindow && currentMapViewOnMain() === view)
                    val camera = map.cameraPosition
                    val target = checkNotNull(camera.target)
                    result.complete(CameraSnapshot(target.latitude, target.longitude, camera.zoom, camera.bearing, camera.tilt))
                } catch (_: Throwable) {
                    result.completeExceptionally(AssertionError("실제 native camera readback을 완료하지 못했습니다"))
                }
            }
        }
        return withTimeout(5_000) { result.await() }
    }

    private suspend fun restoreCamera(
        view: MapView,
        camera: CameraSnapshot,
    ) {
        val restored = CompletableDeferred<Unit>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("소유한 살아 있는 native View만 camera를 복원합니다", view.isAttachedToWindow && currentMapViewOnMain() === view)
            view.getMapAsync { map ->
                try {
                    assertTrue("camera 복원은 UI 스레드에서만 실행합니다", Looper.myLooper() == Looper.getMainLooper())
                    assertTrue("camera 복원 직전에 동일 View여야 합니다", view.isAttachedToWindow && currentMapViewOnMain() === view)
                    map.cancelTransitions()
                    map.cancelAllVelocityAnimations()
                    val position =
                        CameraPosition
                            .Builder()
                            .target(LatLng(camera.latitude, camera.longitude))
                            .zoom(camera.zoom)
                            .bearing(camera.bearing)
                            .tilt(camera.tilt)
                            .build()
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
                    restored.complete(Unit)
                } catch (_: Throwable) {
                    restored.completeExceptionally(AssertionError("합성 pan 양성 대조의 camera를 복원하지 못했습니다"))
                }
            }
        }
        withTimeout(5_000) { restored.await() }
    }

    private fun sameCamera(
        a: CameraSnapshot,
        b: CameraSnapshot,
    ): Boolean =
        abs(a.latitude - b.latitude) <= 0.000000001 && abs(a.longitude - b.longitude) <= 0.000000001 &&
            abs(a.zoom - b.zoom) <= 0.000000001 && abs(a.bearing - b.bearing) <= 0.000000001 && abs(a.tilt - b.tilt) <= 0.000000001

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
                            val dark =
                                view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                                    Configuration.UI_MODE_NIGHT_YES
                            val expectedStyleUri = if (dark) "https://tiles.openfreemap.org/styles/dark" else STYLE_URI
                            val styleReady = style != null && style.isFullyLoaded && style.uri == expectedStyleUri
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

    private fun nodes(tag: String) =
        compose
            .onAllNodes(hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentLiveState<T>(
        initial: T,
        private val emitted: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by emitted {
        @Volatile private var current = initial
        override val value: T get() = current

        fun setWithoutEmission(value: T) {
            current = value
        }
    }

    private class FixturePreparation(
        selection: PracticeSelection,
    ) : PracticeRepository {
        val values = MutableStateFlow<PracticeSelection?>(selection)
        val active = AtomicInteger(0)
        val writes = AtomicInteger(0)

        override fun observeSelection(): Flow<PracticeSelection?> =
            flow {
                active.incrementAndGet()
                try {
                    emitAll(values)
                } finally {
                    active.decrementAndGet()
                }
            }

        override suspend fun saveSelection(
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): Boolean {
            writes.incrementAndGet()
            error("Synthetic map input must never write preparation")
        }
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

    private companion object {
        const val GESTURE_STEPS = 14
        const val SOURCE_ID = "hangil-course-preview-points"
        const val CIRCLES_ID = "hangil-course-preview-circles"
        const val NUMBERS_ID = "hangil-course-preview-numbers"
        const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

        fun parked(): VehicleState =
            VehicleState(
                connection = ConnectionState.CONNECTED,
                gear = VehicleReading.Available(GearPosition.PARK),
            )
    }
}
