package io.github.hangillab.hangil.feature.map

import android.os.Bundle
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat

/** Preserve any existing virtual-node information while guarding its separate action entrypoint. */
internal class MapInputAccessibilityNodeProvider(
    private val original: AccessibilityNodeProviderCompat,
    private val canAct: () -> Boolean,
    private val onDenied: () -> Unit,
) : AccessibilityNodeProviderCompat() {
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat? =
        original.createAccessibilityNodeInfo(virtualViewId)

    override fun findAccessibilityNodeInfosByText(
        text: String,
        virtualViewId: Int,
    ): MutableList<AccessibilityNodeInfoCompat>? = original.findAccessibilityNodeInfosByText(text, virtualViewId)

    override fun findFocus(focus: Int): AccessibilityNodeInfoCompat? = original.findFocus(focus)

    override fun addExtraDataToAccessibilityNodeInfo(
        virtualViewId: Int,
        info: AccessibilityNodeInfoCompat,
        extraDataKey: String,
        arguments: Bundle?,
    ) = original.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments)

    override fun performAction(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        if (!mapAccessibilityFocusAction(action) && !canAct()) {
            onDenied()
            return false
        }
        return original.performAction(virtualViewId, action, arguments)
    }
}
