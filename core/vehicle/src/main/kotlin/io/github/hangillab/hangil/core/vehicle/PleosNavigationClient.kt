package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** One explicit screen request, no route, location, information, or cancellation APIs. */
internal class PleosNavigationClient(
    context: Context,
) : NavigationClient {
    private val context = SdkBindingContext(context)
    private val handler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val requestError = AtomicBoolean(false)
    private var sdk: NaviHelper? = null
    private var closed = false
    private var listenerAdded = false
    private val listener =
        object : NaviHelperEventListener {
            override fun onError(errorCode: NaviErrorCode) {
                requestError.set(true)
            }
        }

    override fun isSupported(): Boolean =
        try {
            context.packageManager.getPackageInfo("ai.pleos.playground.service.navi", 0).longVersionCode >= 11
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    override fun hasPermission(): Boolean =
        context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") == PackageManager.PERMISSION_GRANTED

    override fun connect(
        onConnected: () -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        check(sdk == null && !closed && !cancelled.get())
        val current = NaviHelper(context).also { sdk = it }
        current.initialize(
            onConnected = { handler.post { if (!cancelled.get()) onConnected() } },
            onFailed = { cause -> handler.post { if (!cancelled.get()) onFailed(cause) } },
        )
    }

    override fun requestForeground() {
        check(!closed && !cancelled.get())
        if (context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException()
        }
        val current = checkNotNull(sdk)
        current.addListener(listener)
        listenerAdded = true
        current.requestNavigationScreen(NavigationScreenRequest.Foreground)
        // Synchronous errors are observed; the void return is never an actual-screen acknowledgement.
        check(!requestError.get()) { "Navigation screen request failed" }
    }

    override fun cancelPending() {
        cancelled.set(true)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        var failure: Exception? = null
        try {
            context.close()
        } catch (cause: Exception) {
            failure = cause
        }
        val current = sdk
        if (current != null) {
            if (listenerAdded) {
                try {
                    current.removeListener(listener)
                } catch (cause: Exception) {
                    val previous = failure
                    if (previous == null) failure = cause else previous.addSuppressed(cause)
                }
            }
            try {
                current.release()
            } catch (cause: Exception) {
                val previous = failure
                if (previous == null) failure = cause else previous.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }
}
