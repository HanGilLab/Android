package io.github.hangillab.hangil.feature.map

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.widgets.CompassView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native input checks happen before child dispatch, independently of delayed Compose state.
 * Supports direct touch gestures and immediate pointer-wheel zoom. Inertia, double-tap,
 * quick-zoom, camera keys and trackball input are deliberately unsupported. BACK and system
 * keys still use the normal View path. Programmatic renderer camera fitting is separate.
 */
internal class ParkedMapView(
    context: Context,
    options: MapLibreMapOptions,
    private val canInteract: () -> Boolean,
) : MapView(context, options) {
    // MapView's superclass constructor may call onViewAdded before these fields exist.
    private var initialized = false
    private val input = MapInputEpoch()
    private var boundMap: MapLibreMap? = null
    private var currentAttribution: GuardedAttributionDialogManager? = null
    private var attributionView: ImageView? = null
    private var released = false
    private var sdkStarted = false
    private var revoking = false
    private var cleanupTouch = false
    private var forwardingTouch = false
    private var forwardedEpoch = 0L
    private var lastTouch: MotionEvent? = null
    private var permissionOwner = AtomicBoolean(false)
    private val canvasVisibleRect = Rect()
    private var pointTapAction: ((MapLibreMap, Float, Float, () -> Boolean) -> Unit)? = null
    private val pointTap =
        MapPointTapTracker<TapAction>(
            ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            ViewConfiguration.getLongPressTimeout().toLong(),
        )

    private data class TapAction(
        val deliver: (MapLibreMap, Float, Float, () -> Boolean) -> Unit,
        val owner: AtomicBoolean,
    )

    /** Recomposition may replace the next action; an already accepted DOWN keeps its own action. */
    fun setPointTapAction(action: (MapLibreMap, Float, Float, () -> Boolean) -> Unit) {
        checkMain()
        if (!released) pointTapAction = action
    }

    init {
        initialized = true
        refreshAccessibility(this)
    }

    /** Called from the existing getMapAsync callback on Main, before style loading is acknowledged. */
    fun bindMap(map: MapLibreMap) {
        checkMain()
        if (released) return
        check(boundMap == null || boundMap === map) { "A native map cannot change its bound controller" }
        boundMap = map
        map.uiSettings.apply {
            setAllVelocityAnimationsEnabled(false)
            isDoubleTapGesturesEnabled = false
            isQuickZoomGesturesEnabled = false
        }
        refreshAccessibility(this)
        installCompassClicks(this)
        installAttributionManager()
        recheckInput()
    }

    /** Repeated loading/denied calls are idempotent. A new permitted context gets fresh delegates. */
    fun recheckInput() {
        checkMain()
        if (!initialized || released) return
        val allowed = livePermission()
        val changed = input.updatePermission(allowed, SystemClock.uptimeMillis())
        if (!allowed) {
            permissionOwner.set(false)
            if (changed || lastTouch != null) cancelNativeInput()
        } else if (changed) {
            permissionOwner = AtomicBoolean(true)
            refreshAccessibility(this)
            installCompassClicks(this)
            installAttributionManager()
        }
    }

    override fun onStart() {
        checkMain()
        if (released) return
        super.onStart()
        sdkStarted = true
        input.start(SystemClock.uptimeMillis())
        recheckInput()
    }

    override fun onStop() {
        checkMain()
        if (released) return
        sdkStarted = false
        permissionOwner.set(false)
        input.stop()
        cancelNativeInput()
        super.onStop()
    }

    override fun onDestroy() {
        checkMain()
        if (released) return
        sdkStarted = false
        permissionOwner.set(false)
        input.stop()
        cancelNativeInput()
        released = true
        pointTapAction = null
        boundMap = null
        super.onDestroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (initialized && !released) {
            if (sdkStarted) input.start(SystemClock.uptimeMillis())
            recheckInput()
        }
    }

    override fun onDetachedFromWindow() {
        if (initialized && !released) {
            permissionOwner.set(false)
            input.stop()
            cancelNativeInput()
        }
        super.onDetachedFromWindow()
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (initialized) refreshAccessibility(child)
    }

    override fun initialiseCompassView(): CompassView = super.initialiseCompassView().also { if (initialized) installCompassClick(it) }

    override fun initialiseAttributionView(): ImageView =
        super.initialiseAttributionView().also {
            if (initialized) {
                attributionView = it
                installAttributionClick(it)
            }
        }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        checkMain()
        if (cleanupTouch) return super.dispatchTouchEvent(event)
        return deliverTouch(event) {
            val previousForwarding = forwardingTouch
            val previousEpoch = forwardedEpoch
            forwardingTouch = true
            forwardedEpoch = input.epoch
            try {
                super.dispatchTouchEvent(event)
            } finally {
                forwardingTouch = previousForwarding
                forwardedEpoch = previousEpoch
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        checkMain()
        if (released) return true
        if (cleanupTouch) return super.onTouchEvent(event)
        if (forwardingTouch) {
            if (!ownsContext(forwardedEpoch)) {
                recheckInput()
                return true
            }
            return deliverCanvasTouch(event, forwardedEpoch)
        }
        return deliverTouch(event) { deliverCanvasTouch(event, input.epoch) }
    }

    /** Only the canvas path sees taps; compass/attribution child clicks never become point taps. */
    private fun deliverCanvasTouch(
        event: MotionEvent,
        epoch: Long,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val pointVisible = canvasPointVisible(event.x, event.y)
        if (!pointVisible) pointTap.cancel()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val action = pointTapAction
            if (action != null && event.pointerCount == 1 && pointVisible) {
                pointTap.begin(epoch, event.downTime, now, event.getPointerId(0), event.x, event.y, TapAction(action, permissionOwner))
            }
        }
        for (index in 0 until event.historySize) {
            pointTap.update(
                epoch,
                event.downTime,
                event.getHistoricalEventTime(index),
                now,
                event.pointerCount,
                event.getPointerId(0),
                event.getHistoricalX(index),
                event.getHistoricalY(index),
            )
        }
        pointTap.update(epoch, event.downTime, event.eventTime, now, event.pointerCount, event.getPointerId(0), event.x, event.y)
        val dispatchedTap = pointTap.current()
        // Consume this UP before SDK code may reenter with a newer DOWN.
        val tap = if (event.actionMasked == MotionEvent.ACTION_UP) pointTap.take() else null
        val handled =
            try {
                super.onTouchEvent(event)
            } catch (failure: Throwable) {
                pointTap.discard(dispatchedTap)
                throw failure
            }
        if (!handled) pointTap.discard(dispatchedTap)
        if (handled && tap != null) {
            val map = boundMap
            if (map != null && tap.action.owner.get() && ownsContext(tap.epoch) &&
                input.ownsTouch(event.downTime, event.eventTime, SystemClock.uptimeMillis(), event.deviceId, event.source)
            ) {
                // Persistent selection validity reads only this atomic owner, never VM -> View -> VM.
                tap.action.deliver(map, event.x, event.y, tap.action.owner::get)
            }
        }
        return handled
    }

    private fun canvasPointVisible(
        x: Float,
        y: Float,
    ): Boolean {
        if (!isShown || !x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x >= width || y >= height) return false
        return getLocalVisibleRect(canvasVisibleRect) && x >= canvasVisibleRect.left && y >= canvasVisibleRect.top &&
            x < canvasVisibleRect.right && y < canvasVisibleRect.bottom
    }

    private inline fun deliverTouch(
        event: MotionEvent,
        deliver: () -> Boolean,
    ): Boolean {
        if (released) return true
        recheckInput()
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelNativeTouch()
            input.endTouch()
            return true
        }
        val allowed = livePermission() && input.owns(input.epoch)
        val accepted =
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                cancelNativeTouch()
                input.beginTouch(
                    event.downTime,
                    event.eventTime,
                    SystemClock.uptimeMillis(),
                    event.deviceId,
                    event.source,
                )
            } else {
                input.ownsTouch(
                    event.downTime,
                    event.eventTime,
                    SystemClock.uptimeMillis(),
                    event.deviceId,
                    event.source,
                )
            }
        if (!allowed || !accepted) {
            if (!allowed) recheckInput()
            return true
        }
        rememberTouch(event)
        val deliveredTouch = lastTouch
        val deliveredEpoch = input.epoch
        return try {
            deliver()
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                input.endTouch(deliveredEpoch, event.downTime, event.deviceId, event.source)
                if (lastTouch === deliveredTouch) forgetTouch()
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        checkMain()
        if (released) return true
        if (hover(event)) return super.dispatchGenericMotionEvent(event)
        if (!immediateWheel(event) || !currentInputAllowed() || !currentDiscreteEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        checkMain()
        if (released) return true
        if (hover(event)) return super.onGenericMotionEvent(event)
        if (!immediateWheel(event) || !currentInputAllowed() || !currentDiscreteEvent(event)) return true
        // The pinned SDK's pointer SCROLL path calls NativeMap.setZoom with duration 0.
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = !released && super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        checkMain()
        if (released) return false
        recheckInput()
        return if (mapKey(event.keyCode)) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        !released && (if (mapKey(event.keyCode)) true else super.dispatchKeyEventPreIme(event))

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean =
        !released && (if (mapKey(event.keyCode)) true else super.dispatchKeyShortcutEvent(event))

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = !released && (if (mapKey(keyCode)) true else super.onKeyDown(keyCode, event))

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = !released && (if (mapKey(keyCode)) true else super.onKeyUp(keyCode, event))

    override fun onKeyLongPress(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = !released && (if (mapKey(keyCode)) true else super.onKeyLongPress(keyCode, event))

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
        checkMain()
        recheckInput()
        return true
    }

    override fun onTrackballEvent(event: MotionEvent): Boolean = true

    private fun currentInputAllowed(): Boolean {
        recheckInput()
        return input.owns(input.epoch) && livePermission()
    }

    private fun livePermission(): Boolean = !released && boundMap != null && isAttachedToWindow && canInteract()

    private fun ownsContext(epoch: Long): Boolean = input.owns(epoch) && livePermission()

    /** Retained actions from an old owner cannot cancel or acquire a newer permitted owner. */
    private fun deniedContext(epoch: Long) {
        if (input.epoch == epoch && !livePermission()) recheckInput()
    }

    private fun refreshAccessibility(view: View) {
        val current = ViewCompat.getAccessibilityDelegate(view)
        val epoch = input.epoch
        if (current !is MapInputAccessibilityDelegate || current.epoch != epoch) {
            val original = if (current is MapInputAccessibilityDelegate) current.original else current
            ViewCompat.setAccessibilityDelegate(
                view,
                MapInputAccessibilityDelegate(
                    original,
                    epoch,
                    canAct = { view.isAttachedToWindow && ownsContext(epoch) },
                    onDenied = { deniedContext(epoch) },
                ),
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) refreshAccessibility(view.getChildAt(index))
        }
    }

    private fun installCompassClicks(view: View) {
        if (view is CompassView) installCompassClick(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) installCompassClicks(view.getChildAt(index))
        }
    }

    private fun installCompassClick(view: CompassView) {
        val epoch = input.epoch
        view.setOnClickListener {
            if (!view.isAttachedToWindow || !ownsContext(epoch)) {
                deniedContext(epoch)
            } else {
                val map = boundMap
                if (map != null) {
                    map.cancelTransitions()
                    map.cancelAllVelocityAnimations()
                    val north = CameraPosition.Builder(map.cameraPosition).bearing(0.0).build()
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(north))
                }
            }
        }
    }

    private fun installAttributionClick(view: ImageView) {
        val epoch = input.epoch
        view.setOnClickListener {
            if (view.isAttachedToWindow && ownsContext(epoch)) {
                currentAttribution?.onClick(view)
            } else {
                deniedContext(epoch)
            }
        }
    }

    private fun installAttributionManager() {
        val map = boundMap ?: return
        val epoch = input.epoch
        currentAttribution?.onStop()
        val manager =
            GuardedAttributionDialogManager(
                context,
                map,
                canAct = { ownsContext(epoch) },
                isCurrent = { currentAttribution === it },
                requestOpening = ::openFreshAttribution,
            )
        currentAttribution = manager
        map.uiSettings.setAttributionDialogManager(manager)
        attributionView?.let(::installAttributionClick)
    }

    private fun openFreshAttribution(
        expected: GuardedAttributionDialogManager,
        view: View,
    ) {
        if (currentAttribution !== expected || !currentInputAllowed() || currentAttribution !== expected) return
        // Each SDK dialog captures a distinct manager as its list's OnClickListener.
        installAttributionManager()
        currentAttribution?.show(view)
    }

    private fun cancelNativeInput() {
        if (released || revoking) return
        revoking = true
        try {
            cancelNativeTouch()
            input.endTouch()
            cancelPendingInputEvents()
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            cancel.source = InputDevice.SOURCE_TRACKBALL
            try {
                // Bypass our unsupported-input entrypoint to cancel any SDK timeout safely.
                super.onTrackballEvent(cancel)
            } finally {
                cancel.recycle()
            }
            currentAttribution?.onStop()
            boundMap?.let {
                it.cancelTransitions()
                it.cancelAllVelocityAnimations()
            }
        } finally {
            revoking = false
        }
    }

    private fun cancelNativeTouch() {
        pointTap.cancel()
        val previous = lastTouch ?: return
        lastTouch = null
        val cancel = MotionEvent.obtain(previous)
        cancel.action = MotionEvent.ACTION_CANCEL
        cleanupTouch = true
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cleanupTouch = false
            cancel.recycle()
            previous.recycle()
        }
    }

    private fun rememberTouch(event: MotionEvent) {
        forgetTouch()
        lastTouch = MotionEvent.obtain(event)
    }

    private fun forgetTouch() {
        lastTouch?.recycle()
        lastTouch = null
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Native map input requires the UI thread" }
    }

    private fun currentDiscreteEvent(event: MotionEvent): Boolean =
        input.allowsDiscreteEvent(event.downTime, event.eventTime, SystemClock.uptimeMillis())

    private fun immediateWheel(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)

    private fun hover(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT

    private fun mapKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A,
            -> true
            else -> false
        }
}
