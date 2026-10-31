package io.github.hangillab.hangil.core.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.IdentityHashMap

/**
 * FusedLocation and NaviHelper requests share this per-instance platform ownership boundary.
 * This per-instance context closes the platform binding boundary without claiming to cancel that Job.
 * The SDK's three-argument bind/unbind calls are serialized with close and use owned connection proxies.
 */
internal class SdkBindingContext(
    context: Context,
) : ContextWrapper(context.applicationContext) {
    private val lock = Any()
    private val bindings = IdentityHashMap<ServiceConnection, Binding>()
    private var closed = false
    private var cleanupFailure: Exception? = null

    override fun bindService(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
    ): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            bindings[conn]?.let { previous ->
                if (previous.enabled && previous.bound) return@synchronized true
                unbind(previous)
            }
            val binding = Binding(conn)
            bindings[conn] = binding
            val bound =
                try {
                    baseContext.bindService(service, binding, flags)
                } catch (failure: Exception) {
                    try {
                        unbind(binding)
                    } catch (cleanup: Exception) {
                        throw IllegalStateException("SDK binding cleanup failed", failure).also { it.addSuppressed(cleanup) }
                    }
                    throw failure
                }
            if (!bound) {
                // Android can allocate a ServiceDispatcher even when the platform bind returns false.
                unbind(binding)
            } else {
                binding.bound = true
                binding.drain()
            }
            bound
        }

    override fun unbindService(conn: ServiceConnection) {
        synchronized(lock) {
            bindings[conn]?.let(::unbind)
        }
    }

    fun close() {
        synchronized(lock) {
            // Mark closed before invoking platform unbind: callbacks and a concurrent SDK retry must stop first.
            closed = true
            bindings.values.forEach { it.deactivate() }
            bindings.values.toList().forEach { binding ->
                try {
                    unbind(binding)
                } catch (_: Exception) {
                    // unbind records the actual failure; continue cleaning the other owned dispatchers.
                }
            }
            cleanupFailure?.let { throw it }
        }
    }

    private fun unbind(binding: Binding) {
        binding.deactivate()
        try {
            baseContext.unbindService(binding)
        } catch (_: IllegalArgumentException) {
            // A rejected bind or already-released dispatcher may legitimately have nothing left to unbind.
        } catch (failure: Exception) {
            val previous = cleanupFailure
            if (previous == null) {
                cleanupFailure = failure
            } else if (previous !== failure) {
                previous.addSuppressed(failure)
            }
            throw failure
        }
        if (bindings[binding.delegate] === binding) bindings.remove(binding.delegate)
    }

    private inner class Binding(
        val delegate: ServiceConnection,
    ) : ServiceConnection {
        var enabled = true
        var bound = false
        private val pending = ArrayDeque<() -> Unit>()

        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) = dispatch { delegate.onServiceConnected(name, service) }

        override fun onServiceDisconnected(name: ComponentName) = dispatch { delegate.onServiceDisconnected(name) }

        override fun onBindingDied(name: ComponentName) = dispatch { delegate.onBindingDied(name) }

        override fun onNullBinding(name: ComponentName) = dispatch { delegate.onNullBinding(name) }

        fun deactivate() {
            enabled = false
            pending.clear()
        }

        fun drain() {
            while (pending.isNotEmpty() && isCurrent()) pending.removeFirst().invoke()
        }

        private fun dispatch(block: () -> Unit) {
            synchronized(lock) {
                if (!isCurrent()) return
                if (bound) block() else pending.addLast(block)
            }
        }

        private fun isCurrent() = !closed && enabled && bindings[delegate] === this
    }
}
