package io.github.hangillab.hangil.feature.map

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import org.maplibre.android.maps.AttributionDialogManager
import org.maplibre.android.maps.MapLibreMap

/** One instance owns at most one SDK dialog; its URL listener never borrows a later opening. */
internal class GuardedAttributionDialogManager(
    context: Context,
    map: MapLibreMap,
    private val canAct: () -> Boolean,
    private val isCurrent: (GuardedAttributionDialogManager) -> Boolean,
    private val requestOpening: (GuardedAttributionDialogManager, View) -> Unit,
) : AttributionDialogManager(context, map) {
    private var dialogActive = false

    override fun onClick(view: View) {
        checkMain()
        if (view.isAttachedToWindow && canAct() && isCurrent(this)) requestOpening(this, view)
    }

    fun show(view: View) {
        checkMain()
        if (!view.isAttachedToWindow || !canAct() || !isCurrent(this)) return
        dialogActive = true
        try {
            // Call the superclass once; calling this.onClick would request another owner.
            super.onClick(view)
        } catch (failure: Throwable) {
            dialogActive = false
            throw failure
        }
    }

    override fun onClick(
        dialog: DialogInterface,
        which: Int,
    ) {
        checkMain()
        if (!dialogActive || !canAct() || !isCurrent(this) || (dialog as? Dialog)?.isShowing != true) {
            onStop()
            return
        }
        // Consume before forwarding, so a retained callback cannot launch the same link twice.
        dialogActive = false
        super.onClick(dialog, which)
    }

    override fun onStop() {
        checkMain()
        dialogActive = false
        super.onStop()
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Map attribution requires the UI thread" }
    }
}
