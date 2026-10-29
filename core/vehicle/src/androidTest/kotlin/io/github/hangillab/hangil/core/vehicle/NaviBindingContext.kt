package io.github.hangillab.hangil.core.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.IdentityHashMap

/** Observes real SDK bindings without blocking retries, callbacks, or platform cleanup. */
internal class NaviBindingContext(
    context: Context,
) : ContextWrapper(context) {
    private val lock = Any()
    private val bindings = IdentityHashMap<ServiceConnection, Binding>()
    private var bindCalls = 0
    private var successfulBinds = 0
    private var unbindCalls = 0
    private var connectedCallbacks = 0
    private var callbacksAfterUnbind = 0
    private var cleanupFailures = 0

    override fun bindService(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
    ): Boolean =
        synchronized(lock) {
            ++bindCalls
            val binding = bindings.getOrPut(conn) { Binding(conn) }
            binding.active = true
            baseContext.bindService(service, binding, flags).also { bound ->
                binding.bound = bound
                if (bound) ++successfulBinds
            }
        }

    override fun unbindService(conn: ServiceConnection) {
        synchronized(lock) {
            ++unbindCalls
            val binding = checkNotNull(bindings[conn]) { "SDK attempted to unbind an unknown connection" }
            try {
                baseContext.unbindService(binding)
                binding.active = false
                binding.bound = false
            } catch (failure: IllegalArgumentException) {
                if (binding.bound) ++cleanupFailures else binding.active = false
                throw failure
            } catch (failure: Exception) {
                ++cleanupFailures
                throw failure
            }
        }
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            Snapshot(
                bindCalls,
                successfulBinds,
                unbindCalls,
                bindings.values.count { it.active },
                connectedCallbacks,
                callbacksAfterUnbind,
                cleanupFailures,
            )
        }

    data class Snapshot(
        val bindCalls: Int,
        val successfulBinds: Int,
        val unbindCalls: Int,
        val activeBindings: Int,
        val connectedCallbacks: Int,
        val callbacksAfterUnbind: Int,
        val cleanupFailures: Int,
    )

    private inner class Binding(
        private val delegate: ServiceConnection,
    ) : ServiceConnection {
        var active = false
        var bound = false

        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) {
            synchronized(lock) {
                ++connectedCallbacks
                if (!active) ++callbacksAfterUnbind
            }
            delegate.onServiceConnected(name, service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordLateCallback()
            delegate.onServiceDisconnected(name)
        }

        override fun onBindingDied(name: ComponentName) {
            recordLateCallback()
            delegate.onBindingDied(name)
        }

        override fun onNullBinding(name: ComponentName) {
            recordLateCallback()
            delegate.onNullBinding(name)
        }

        private fun recordLateCallback() {
            synchronized(lock) { if (!active) ++callbacksAfterUnbind }
        }
    }
}
