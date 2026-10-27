package io.github.hangillab.hangil.feature.map

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import org.maplibre.android.maps.MapView

internal class MapViewSavedState(
    var bundle: Bundle? = null,
) {
    private var attachedView: MapView? = null

    fun attach(view: MapView) {
        attachedView = view
    }

    fun detach(view: MapView) {
        if (attachedView === view) {
            bundle = Bundle().also(view::onSaveInstanceState)
            attachedView = null
        }
    }

    private fun save(): Bundle = attachedView?.let { Bundle().also(it::onSaveInstanceState) } ?: bundle ?: Bundle()

    companion object {
        val Saver = Saver<MapViewSavedState, Bundle>(save = { it.save() }, restore = { MapViewSavedState(it) })
    }
}
