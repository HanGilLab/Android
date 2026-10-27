package io.github.hangillab.hangil.feature.map

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.maps.MapView

/** Balances native callbacks even when a parked-only map leaves composition before its Activity. */
internal class MapViewLifecycle(
    private val view: MapView,
    private val savedState: MapViewSavedState,
    private val onReleased: () -> Unit,
) : DefaultLifecycleObserver {
    private var started = false
    private var resumed = false
    private var destroyed = false

    override fun onStart(owner: LifecycleOwner) = start()

    override fun onResume(owner: LifecycleOwner) = resume()

    override fun onPause(owner: LifecycleOwner) = pause()

    override fun onStop(owner: LifecycleOwner) = stop()

    override fun onDestroy(owner: LifecycleOwner) = destroy()

    fun synchronize(state: Lifecycle.State) {
        if (state == Lifecycle.State.DESTROYED) {
            destroy()
        } else {
            if (state.isAtLeast(Lifecycle.State.STARTED)) start()
            if (state.isAtLeast(Lifecycle.State.RESUMED)) resume()
        }
    }

    fun lowMemory() {
        if (!destroyed) view.onLowMemory()
    }

    fun destroy() {
        if (destroyed) return
        onReleased()
        pause()
        stop()
        savedState.detach(view)
        view.onDestroy()
        destroyed = true
    }

    private fun start() {
        if (!started && !destroyed) {
            view.onStart()
            started = true
        }
    }

    private fun resume() {
        if (!resumed && !destroyed) {
            start()
            view.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed) {
            view.onPause()
            resumed = false
        }
    }

    private fun stop() {
        pause()
        if (started) {
            view.onStop()
            started = false
        }
    }
}
