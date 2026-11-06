package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.PracticeNavigationRoute
import java.util.concurrent.atomic.AtomicBoolean

/** One explicit screen or guidance request and its associated SDK connection. */
internal class PleosNavigationClient(
    context: Context,
    initialConnectionOnly: Boolean = false,
) : NavigationClient {
    private val context = SdkBindingContext(context, initialConnectionOnly)
    private val handler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private var sdk: NaviHelper? = null
    private var closed = false
    private var listener: NaviHelperEventListener? = null

    override fun isSupported(): Boolean =
        try {
            context.packageManager.getPackageInfo("ai.pleos.playground.service.navi", 0).longVersionCode >= 11
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    override fun hasPermission(): Boolean = hasPermission("pleos.car.permission.NAVI_CUSTOM_MAP")

    override fun hasRoutePermissions(): Boolean =
        hasPermission("pleos.car.permission.NAVI_ROUTE") && hasPermission("pleos.car.permission.NAVI_ROUTE_SEARCH")

    private fun hasPermission(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

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
        if (!hasPermission()) throw SecurityException()
        val current = checkNotNull(sdk)
        val requestError = AtomicBoolean(false)
        val screenListener =
            object : NaviHelperEventListener {
                override fun onError(errorCode: NaviErrorCode) {
                    requestError.set(true)
                }
            }
        listener = screenListener
        current.addListener(screenListener)
        current.requestNavigationScreen(NavigationScreenRequest.Foreground)
        // Synchronous errors are observed; the void return is never an actual-screen acknowledgement.
        check(!requestError.get()) { "Navigation screen request failed" }
    }

    override suspend fun requestRoute(
        route: PracticeNavigationRoute,
        canDispatch: () -> Boolean,
        timeoutMillis: Long,
    ): NavigationScreenResult {
        check(!closed && !cancelled.get())
        val current = checkNotNull(sdk)
        val routeRequest =
            PleosNavigationRouteRequest(
                onConnection = context::onInitialConnection,
                hasPermissions = ::hasRoutePermissions,
                canDispatch = canDispatch,
                getRouteState = current::getRouteStateInfo,
                getDestination = current::getDestinationInfo,
                getWaypoints = current::getWaypointInfo,
                requestRoute = current::requestRouteWithWaypoints,
            )
        listener = routeRequest
        current.addListener(routeRequest)
        return routeRequest.request(route, timeoutMillis)
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
            listener?.let { added ->
                try {
                    current.removeListener(added)
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
