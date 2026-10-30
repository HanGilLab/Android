package io.github.hangillab.hangil

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.AdapterView
import android.widget.ListView
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
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
import org.maplibre.android.R as MapSdkR

/**
 * Additional controlled native boundaries after the input guard is implemented. Vehicle and
 * preparation are explicit synthetic fixtures; real MapRoute/VM/MapLibre/native dispatch and
 * public style network are used. No Room/real SDK gateway, captures or performance claims.
 * Native camera key/trackball control is intentionally unsupported, so only no-mutation is
 * asserted for those events. Touch drag/pinch remain positive native controls.
 * Attribution uses public own-process WindowInspector/ListView callbacks with the real dialog;
 * ActivityMonitor blocks/counts ACTION_VIEW attempts without opening an external browser.
 * Only the first actual dialog item is replayed. Hardware delivery, generic wheel/hover and
 * real DDS fault injection are not claimed by these controlled dispatch tests.
 */
@RunWith(AndroidJUnit4::class)
class MapInputLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun freshParkedPanAndPinchChangeTheRealNativeCamera() =
        withMap { _, initial ->
            dispatchHorizontalPan(initial.view)
            val panned = awaitStableCamera(initial.view)
            assertTrue(
                "허용된 직접 drag는 실제 camera 중심을 움직여야 합니다",
                abs(initial.camera.latitude - panned.latitude) > 0.000001 ||
                    abs(initial.camera.longitude - panned.longitude) > 0.000001,
            )
            restoreCamera(initial.view, initial.camera)
            assertTrue("같은 camera로 복원한 뒤 pinch를 검사합니다", sameCamera(initial.camera, awaitStableCamera(initial.view)))
            dispatchPinch(initial.view)
            val zoomed = awaitStableCamera(initial.view)
            assertTrue("허용된 직접 pinch는 실제 SDK zoom을 바꿔야 합니다", abs(zoomed.zoom - initial.camera.zoom) > 0.1)
        }

    @Test fun nativeImmediatePointerWheelZoomRequiresTheLiveParkedGate() =
        withMap { fixture, initial ->
            sendWheel(initial.view)
            val zoomed = awaitStableCamera(initial.view)
            assertTrue("공개 native wheel 경로는 허용 P에서 실제 zoom을 바꿔야 합니다", abs(zoomed.zoom - initial.camera.zoom) > 0.1)
            restoreCamera(initial.view, initial.camera)
            val restored = awaitStableCamera(initial.view)
            assertTrue("같은 camera에서 거부 비교를 시작합니다", sameCamera(initial.camera, restored))
            val oldReady = fixture.model.uiState.value
            compose.runOnIdle {
                fixture.vehicleState.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
                assertTrue("무방출 D는 옛 P/Ready를 남깁니다", fixture.model.uiState.value == oldReady)
            }
            sendWheel(initial.view)
            delay(600)
            assertTrue("즉시 wheel도 최신 D에서 camera를 바꾸면 안 됩니다", sameCamera(restored, awaitStableCamera(initial.view)))
        }

    /** Public native dispatch, with real SDK camera readback; this is not hardware mouse delivery. */
    private fun sendWheel(view: MapView) =
        onMain {
            assertTrue(view.isAttachedToWindow && currentMapViewOnMain() === view)
            val now = SystemClock.uptimeMillis()
            val properties =
                arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_MOUSE
                    },
                )
            val coordinates =
                arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = view.width * 0.5f
                        y = view.height * 0.5f
                        setAxisValue(MotionEvent.AXIS_VSCROLL, 1f)
                    },
                )
            val event =
                MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_SCROLL,
                    1,
                    properties,
                    coordinates,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_MOUSE,
                    0,
                )
            try {
                view.dispatchGenericMotionEvent(event)
            } finally {
                event.recycle()
            }
        }

    @Test fun aTouchStreamStartedInSilentDCannotActivateWhenPReturns() =
        withMap { fixture, initial ->
            val oldReady = fixture.model.uiState.value
            val downTime = SystemClock.uptimeMillis()
            compose.runOnIdle {
                fixture.vehicleState.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
                assertTrue("합성 D는 방출 없이 옛 P/Ready를 남겨야 합니다", fixture.model.uiState.value == oldReady)
            }
            sendTouch(initial.view, downTime, MotionEvent.ACTION_DOWN, 0.38f, throughActivity = true)
            compose.runOnIdle { fixture.vehicleState.setWithoutEmission(parked()) }
            sendTouch(initial.view, downTime, MotionEvent.ACTION_MOVE, 0.52f, throughActivity = true)
            delay(60)
            sendTouch(initial.view, downTime, MotionEvent.ACTION_MOVE, 0.66f, throughActivity = true)
            sendTouch(initial.view, downTime, MotionEvent.ACTION_UP, 0.66f, throughActivity = true)
            delay(1_000)
            assertTrue(
                "거부한 DOWN의 MOVE/UP가 P 복귀 뒤 camera를 움직이면 안 됩니다",
                sameCamera(initial.camera, awaitStableCamera(initial.view)),
            )
            dispatchHorizontalPan(initial.view)
            val fresh = awaitStableCamera(initial.view)
            assertTrue(
                "새 P DOWN으로 시작한 drag는 계속 허용해야 합니다",
                abs(fresh.longitude - initial.camera.longitude) > 0.000001 ||
                    abs(fresh.latitude - initial.camera.latitude) > 0.000001,
            )
        }

    @Test fun stoppedStreamsAndUnsupportedCameraKeysNeverReviveUnderAFreshOwner() =
        withMap { fixture, initial ->
            val oldView = initial.view
            val oldDownTime = SystemClock.uptimeMillis()
            sendTouch(oldView, oldDownTime, MotionEvent.ACTION_DOWN, 0.38f, throughActivity = true)
            onMain {
                oldView.requestFocus()
                oldView.dispatchKeyEvent(
                    KeyEvent(
                        oldDownTime,
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        0,
                    ),
                )
                dispatchTrackball(oldView, oldDownTime, MotionEvent.ACTION_DOWN)
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitMapReady()
            compose.onNodeWithTag("map_view", useUnmergedTree = true).performScrollTo()
            val fresh = awaitPreview(fixture.preview)
            val restored = awaitStableCamera(fresh.view)
            // Stale View dispatch is exercised, but no JNI/camera query is ever made on that View.
            for (target in listOf(oldView, fresh.view).distinct()) {
                sendTouch(target, oldDownTime, MotionEvent.ACTION_MOVE, 0.62f, throughActivity = false)
                sendTouch(target, oldDownTime, MotionEvent.ACTION_UP, 0.62f, throughActivity = false)
                onMain {
                    target.dispatchKeyEvent(
                        KeyEvent(
                            oldDownTime,
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            1,
                            0,
                            0,
                            0,
                            KeyEvent.FLAG_LONG_PRESS or KeyEvent.FLAG_CANCELED,
                        ),
                    )
                    target.dispatchKeyEvent(
                        KeyEvent(
                            oldDownTime,
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            0,
                        ),
                    )
                    dispatchTrackball(target, oldDownTime, MotionEvent.ACTION_UP)
                }
            }
            delay(ViewConfiguration.getLongPressTimeout().toLong() + 700)
            assertTrue(
                "STOP 이전 입력/예약 동작은 새 View의 camera를 바꾸면 안 됩니다",
                sameCamera(restored, awaitStableCamera(fresh.view)),
            )
            // These SDK camera controls are explicitly unsupported even at P, not positive controls.
            val unsupportedDown = SystemClock.uptimeMillis()
            onMain {
                fresh.view.requestFocus()
                fresh.view.dispatchKeyEvent(
                    KeyEvent(
                        unsupportedDown,
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        0,
                    ),
                )
                fresh.view.dispatchKeyEvent(
                    KeyEvent(
                        unsupportedDown,
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        0,
                    ),
                )
                dispatchTrackball(fresh.view, unsupportedDown, MotionEvent.ACTION_DOWN)
                dispatchTrackball(fresh.view, unsupportedDown, MotionEvent.ACTION_MOVE)
                dispatchTrackball(fresh.view, unsupportedDown, MotionEvent.ACTION_UP)
            }
            delay(ViewConfiguration.getLongPressTimeout().toLong() + 700)
            assertTrue(
                "명시 미지원 keyboard/trackball camera 제어가 지연 실행되면 안 됩니다",
                sameCamera(restored, awaitStableCamera(fresh.view)),
            )
            dispatchPinch(fresh.view)
            assertTrue("새 START/P의 직접 pinch는 동작해야 합니다", abs(awaitStableCamera(fresh.view).zoom - restored.zoom) > 0.1)
        }

    @Test fun silentDRejectsActualCompassAndAttributionAccessibilityClicks() =
        withMap { fixture, initial ->
            val tilted = initial.camera.copy(bearing = 35.0)
            restoreCamera(initial.view, tilted)
            awaitStableCamera(initial.view)
            delay(600)
            val compass = nativeChild(initial.view, MapSdkR.string.maplibre_compassContentDescription)
            onMain { compass.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
            val north = awaitStableCamera(initial.view)
            assertTrue("P의 실제 compass 접근성 동작은 북쪽으로 camera를 바꿔야 합니다", abs(north.bearing) < 0.1)
            restoreCamera(initial.view, tilted)
            val beforeD = awaitStableCamera(initial.view)
            delay(600)
            val attribution = nativeChild(initial.view, MapSdkR.string.maplibre_attributionsIconContentDescription)
            compose.runOnIdle {
                fixture.vehicleState.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
            }
            onMain {
                compass.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                attribution.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            }
            delay(700)
            assertTrue("D의 compass 접근성 동작은 camera를 바꾸면 안 됩니다", sameCamera(beforeD, awaitStableCamera(initial.view)))
            assertTrue("D의 attribution 접근성 동작은 대화상자를 만들면 안 됩니다", attributionLists().isEmpty())
            compose.runOnIdle { fixture.vehicleState.setWithoutEmission(parked()) }
            // Silent fixture values omit the real P receipt notification. Establish a fresh public
            // touch scope and obtain the newly installed child delegate, never promote the old one.
            val freshDown = SystemClock.uptimeMillis()
            sendTouch(initial.view, freshDown, MotionEvent.ACTION_DOWN, 0.48f, throughActivity = true)
            sendTouch(initial.view, freshDown, MotionEvent.ACTION_UP, 0.48f, throughActivity = true)
            val freshAttribution = nativeChild(initial.view, MapSdkR.string.maplibre_attributionsIconContentDescription)
            onMain { freshAttribution.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
            awaitAttributionItem()
            // An actual fresh-P dialog proves attribution information is still reachable.
        }

    @Test fun anOldActualAttributionDialogListenerCannotLaunchAUrlAfterRestart() =
        withMap { fixture, initial ->
            val attempts = AtomicInteger(0)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor =
                object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.action != Intent.ACTION_VIEW) return null
                        attempts.incrementAndGet()
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
            instrumentation.addMonitor(monitor)
            try {
                val attribution = nativeChild(initial.view, MapSdkR.string.maplibre_attributionsIconContentDescription)
                onMain { attribution.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
                val oldItem = awaitAttributionItem()
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                onMain { oldItem.invoke() }
                assertEquals("STOP 중 옛 URL listener는 외부 intent를 만들면 안 됩니다", 0, attempts.get())
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                waitMapReady()
                compose.onNodeWithTag("map_view", useUnmergedTree = true).performScrollTo()
                val fresh = awaitPreview(fixture.preview)
                onMain { oldItem.invoke() }
                assertEquals("옛 실제 Dialog listener는 새 START를 빌리면 안 됩니다", 0, attempts.get())
                val freshAttribution = nativeChild(fresh.view, MapSdkR.string.maplibre_attributionsIconContentDescription)
                onMain { freshAttribution.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
                val currentItem = awaitAttributionItem()
                onMain { currentItem.invoke() }
                assertEquals("새 P 실제 Dialog URL 경로의 양성 대조를 monitor로 차단/확인해야 합니다", 1, attempts.get())
                assertTrue(
                    "외부 브라우저 대신 실제 검사 앱에 남아야 합니다",
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.packageName
                        ?.toString() ==
                        instrumentation.targetContext.packageName,
                )
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }

    private fun withMap(block: suspend (Fixture, NativeReadback) -> Unit) {
        assumeTrue(
            "입력 guard 후 제어 native 경계의 명시적 실행에서만 검사합니다",
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
        onMain {
            val factory =
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        assertTrue("실제 Map VM만 명시적으로 생성합니다", modelClass == MapViewModel::class.java)
                        @Suppress("UNCHECKED_CAST")
                        return MapViewModel(vehicle, restrictions, repository, catalog) as T
                    }
                }
            model = ViewModelProvider(compose.activity, factory)[MapViewModel::class.java]
        }
        val shown = mutableStateOf(true)
        val fixture = Fixture(model, vehicleState, preview)
        var primaryFailure: Throwable? = null
        try {
            compose.setContent { HanGilTheme { if (shown.value) MapRoute(onClose = {}, viewModel = model) } }
            runBlocking {
                waitMapReady()
                compose.onNodeWithTag("map_view", useUnmergedTree = true).performScrollTo()
                block(fixture, awaitPreview(preview))
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                if (!compose.activity.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                }
                onMain { vehicleState.setWithoutEmission(parked()) }
                val attached = currentMapView()
                var dialogCleanupFailure: Throwable? = null
                try {
                    if (attached != null) runBlocking { stopAttributionManager(attached) }
                } catch (failure: Throwable) {
                    dialogCleanupFailure = failure
                }
                try {
                    compose.runOnIdle { shown.value = false }
                    compose.waitForIdle()
                    compose.waitUntil(20_000) { repository.active.get() == 0 && currentMapView() == null }
                    assertEquals("fixture 저장 호출은0이어야 합니다", 0, repository.writes.get())
                    assertTrue("원래 합성 준비를 보존해야 합니다", selection == repository.values.value)
                    assertTrue("소유한 출처 대화상자가 남으면 안 됩니다", attributionLists().isEmpty())
                } catch (failure: Throwable) {
                    dialogCleanupFailure?.let { failure.addSuppressed(it) }
                    throw failure
                }
                dialogCleanupFailure?.let { throw it }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun waitMapReady() {
        compose.waitUntil(90_000) { nodes("map_ready").size == 1 && nodes("map_view").size == 1 }
        compose.waitForIdle()
    }

    private fun sendTouch(
        view: MapView,
        downTime: Long,
        action: Int,
        xFraction: Float,
        throughActivity: Boolean,
    ) {
        onMain {
            val origin = IntArray(2)
            if (throughActivity) view.getLocationInWindow(origin)
            val props =
                arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    },
                )
            val coords =
                arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = origin[0] + view.width * xFraction
                        y = origin[1] + view.height * 0.5f
                        pressure = 1f
                        size = 1f
                    },
                )
            val event =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    1,
                    props,
                    coords,
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
                if (throughActivity) compose.activity.dispatchTouchEvent(event) else view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private suspend fun dispatchPinch(view: MapView) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            onMain {
                assertTrue("P pinch는 살아 있는 현재 View로 전달합니다", currentMapViewOnMain() === view)
                val origin = IntArray(2)
                view.getLocationInWindow(origin)
                val distance = 36f + 8f * step
                val count = if (step == 0 || step == 12) 1 else 2
                val props =
                    Array(count) { index ->
                        MotionEvent.PointerProperties().apply {
                            id = index
                            toolType = MotionEvent.TOOL_TYPE_FINGER
                        }
                    }
                val coords =
                    Array(count) { index ->
                        MotionEvent.PointerCoords().apply {
                            x = origin[0] + view.width * 0.5f + if (index == 0) -distance else distance
                            y = origin[1] + view.height * 0.5f
                            pressure = 1f
                            size = 1f
                        }
                    }
                val action =
                    when (step) {
                        0 -> MotionEvent.ACTION_DOWN
                        1 -> MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        11 -> MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        12 -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                val event =
                    MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        action,
                        count,
                        props,
                        coords,
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
                    compose.activity.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            if (step < 12) delay(30)
        }
    }

    private fun dispatchTrackball(
        view: MapView,
        downTime: Long,
        action: Int,
    ) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 0.25f, 0f, 0)
        event.source = InputDevice.SOURCE_TRACKBALL
        try {
            view.dispatchTrackballEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun nativeChild(
        view: MapView,
        description: Int,
    ): View {
        var child: View? = null
        onMain {
            val text = view.context.getString(description)
            val choices = descendants(view).filter { it.isAttachedToWindow && it.contentDescription?.toString() == text }
            child = choices.singleOrNull()
            assertTrue("공개 SDK 설명으로 실제 자식 제어를 찾아야 합니다", child != null && child?.isShown == true)
        }
        return checkNotNull(child)
    }

    private suspend fun awaitAttributionItem(): CapturedDialogItem =
        withTimeout(10_000) {
            var captured: CapturedDialogItem? = null
            while (captured == null) {
                onMain {
                    val list = attributionListsOnMain().singleOrNull()
                    if (list != null && list.adapter != null && list.adapter.count > 0 && list.childCount > 0) {
                        val callback = list.onItemClickListener
                        if (callback != null) {
                            captured = CapturedDialogItem(list, callback, list.getChildAt(0), list.adapter.getItemId(0))
                        }
                    }
                }
                if (captured == null) delay(100)
            }
            checkNotNull(captured)
        }

    private fun attributionLists(): List<ListView> {
        var found = emptyList<ListView>()
        onMain { found = attributionListsOnMain() }
        return found
    }

    private fun attributionListsOnMain(): List<ListView> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(MapSdkR.string.maplibre_attributionsDialogTitle)
        return WindowInspector
            .getGlobalWindowViews()
            .filter { root ->
                root.isAttachedToWindow && descendants(root).filterIsInstance<TextView>().any { it.text?.toString() == title }
            }.flatMap(::descendants)
            .filterIsInstance<ListView>()
            .filter { it.isShown }
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private suspend fun stopAttributionManager(view: MapView) {
        val completed = CompletableDeferred<Unit>()
        onMain {
            if (!view.isAttachedToWindow || currentMapViewOnMain() !== view) {
                completed.complete(Unit)
            } else {
                view.getMapAsync { map ->
                    try {
                        if (view.isAttachedToWindow && currentMapViewOnMain() === view) {
                            checkNotNull(map.uiSettings.attributionDialogManager).onStop()
                        }
                        completed.complete(Unit)
                    } catch (_: Throwable) {
                        completed.completeExceptionally(AssertionError("소유 출처 창을 정리하지 못했습니다"))
                    }
                }
            }
        }
        withTimeout(5_000) { completed.await() }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

    private data class CapturedDialogItem(
        val list: ListView,
        val listener: AdapterView.OnItemClickListener,
        val row: View?,
        val itemId: Long,
    ) {
        fun invoke() {
            listener.onItemClick(list, row, 0, itemId)
        }
    }

    private data class Fixture(
        val model: MapViewModel,
        val vehicleState: SilentLiveState<VehicleState>,
        val preview: CourseMapPreview,
    )

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
