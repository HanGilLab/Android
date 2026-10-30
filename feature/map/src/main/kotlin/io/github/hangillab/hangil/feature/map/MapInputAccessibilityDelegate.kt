package io.github.hangillab.hangil.feature.map

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat

/** Captures one input epoch; refreshing a View never upgrades a previously retained delegate. */
internal class MapInputAccessibilityDelegate(
    val original: AccessibilityDelegateCompat?,
    val epoch: Long,
    private val canAct: () -> Boolean,
    private val onDenied: () -> Unit,
) : AccessibilityDelegateCompat() {
    override fun sendAccessibilityEvent(
        host: View,
        eventType: Int,
    ) {
        original?.sendAccessibilityEvent(host, eventType) ?: super.sendAccessibilityEvent(host, eventType)
    }

    override fun sendAccessibilityEventUnchecked(
        host: View,
        event: AccessibilityEvent,
    ) {
        original?.sendAccessibilityEventUnchecked(host, event) ?: super.sendAccessibilityEventUnchecked(host, event)
    }

    override fun dispatchPopulateAccessibilityEvent(
        host: View,
        event: AccessibilityEvent,
    ): Boolean = original?.dispatchPopulateAccessibilityEvent(host, event) ?: super.dispatchPopulateAccessibilityEvent(host, event)

    override fun onPopulateAccessibilityEvent(
        host: View,
        event: AccessibilityEvent,
    ) {
        original?.onPopulateAccessibilityEvent(host, event) ?: super.onPopulateAccessibilityEvent(host, event)
    }

    override fun onInitializeAccessibilityEvent(
        host: View,
        event: AccessibilityEvent,
    ) {
        original?.onInitializeAccessibilityEvent(host, event) ?: super.onInitializeAccessibilityEvent(host, event)
    }

    override fun onInitializeAccessibilityNodeInfo(
        host: View,
        info: AccessibilityNodeInfoCompat,
    ) {
        original?.onInitializeAccessibilityNodeInfo(host, info) ?: super.onInitializeAccessibilityNodeInfo(host, info)
    }

    override fun onRequestSendAccessibilityEvent(
        host: ViewGroup,
        child: View,
        event: AccessibilityEvent,
    ): Boolean = original?.onRequestSendAccessibilityEvent(host, child, event) ?: super.onRequestSendAccessibilityEvent(host, child, event)

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? {
        val provider = original?.getAccessibilityNodeProvider(host) ?: super.getAccessibilityNodeProvider(host)
        return provider?.let { MapInputAccessibilityNodeProvider(it, canAct, onDenied) }
    }

    override fun performAccessibilityAction(
        host: View,
        action: Int,
        args: Bundle?,
    ): Boolean {
        if (!mapAccessibilityFocusAction(action) && !canAct()) {
            onDenied()
            return false
        }
        return original?.performAccessibilityAction(host, action, args) ?: super.performAccessibilityAction(host, action, args)
    }
}

/** Focusing/clearing a native node is preserved; activation and scroll remain guarded. */
internal fun mapAccessibilityFocusAction(action: Int): Boolean =
    action == AccessibilityNodeInfo.ACTION_FOCUS || action == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS ||
        action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS || action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
