package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.PracticeNavigationRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

internal class PleosNavigationRouteRequest(
    private val onConnection: (() -> Unit) -> Boolean,
    private val hasPermissions: () -> Boolean,
    private val canDispatch: () -> Boolean,
    private val getRouteState: () -> Unit,
    private val getDestination: () -> Unit,
    private val getWaypoints: () -> Unit,
    private val requestRoute: (RouteWithWaypointsRequest) -> Unit,
) : NaviHelperEventListener {
    private val sdkError = CompletableDeferred<Unit>()
    private val state = AtomicReference<CompletableDeferred<RouteStateInfo>?>()
    private val destination = AtomicReference<CompletableDeferred<DestinationInfo>?>()
    private val waypoints = AtomicReference<CompletableDeferred<List<WaypointInfo>>?>()

    suspend fun request(
        route: PracticeNavigationRoute,
        timeoutMillis: Long,
    ): NavigationScreenResult {
        val command = route.toSdkRequest()
        var attempted = false
        val unconfirmed = NavigationScreenResult.GUIDANCE_UNCONFIRMED
        try {
            return withTimeoutOrNull(timeoutMillis) {
                val before = read(state, getRouteState) ?: return@withTimeoutOrNull gateFailure() ?: NavigationScreenResult.ERROR
                if (before.isRouting || before.naviMode == NaviMode.ROUTE_GUIDING || before.naviMode == NaviMode.SIMULATION_GUIDING) {
                    return@withTimeoutOrNull NavigationScreenResult.ACTIVE_ROUTE
                }
                if (!before.termsAgreement || before.naviMode != NaviMode.SAFE_GUIDING) {
                    return@withTimeoutOrNull NavigationScreenResult.ERROR
                }

                var rejected = NavigationScreenResult.ERROR
                currentCoroutineContext().ensureActive()
                onConnection {
                    val gate = gateFailure()
                    if (gate != null) {
                        rejected = gate
                    } else if (!sdkError.isCompleted) {
                        attempted = true
                        requestRoute(command)
                    }
                }
                if (!attempted) return@withTimeoutOrNull rejected

                // Route events depend on the native app. Read the active route after sending once.
                if (!awaitGuidance()) return@withTimeoutOrNull unconfirmed
                val target = read(destination, getDestination) ?: return@withTimeoutOrNull unconfirmed
                if (!route.destination.matchesProviderPoi(target.poiId, target.poiSubId)) {
                    return@withTimeoutOrNull unconfirmed
                }
                val stops = read(waypoints, getWaypoints) ?: return@withTimeoutOrNull unconfirmed
                if (stops.size != route.waypoints.size ||
                    route.waypoints.zip(stops).any { (point, stop) -> !point.matchesProviderPoi(stop.poiId, stop.poiSubId) }
                ) {
                    return@withTimeoutOrNull unconfirmed
                }
                val finalState = read(state, getRouteState)
                var confirmed = false
                onConnection {
                    confirmed = finalState?.isGuiding() == true && gateFailure() == null && !sdkError.isCompleted
                }
                if (confirmed) NavigationScreenResult.GUIDANCE_STARTED else unconfirmed
            } ?: if (attempted) unconfirmed else NavigationScreenResult.TIMED_OUT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (attempted) return unconfirmed
            throw failure
        }
    }

    override fun onError(errorCode: NaviErrorCode) {
        sdkError.complete(Unit)
    }

    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
        state.get()?.complete(routeStateInfo)
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        destination.get()?.complete(destinationInfo)
    }

    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
        waypoints.get()?.complete(waypointInfoList.toList())
    }

    private suspend fun awaitGuidance(): Boolean {
        while (true) {
            delay(250)
            val current = read(state, getRouteState) ?: return false
            if (current.isGuiding()) return true
            if (!current.termsAgreement || current.naviMode == NaviMode.SIMULATION_GUIDING) return false
        }
    }

    private suspend fun <T> read(
        pending: AtomicReference<CompletableDeferred<T>?>,
        query: () -> Unit,
    ): T? {
        currentCoroutineContext().ensureActive()
        val result = CompletableDeferred<T>()
        try {
            var sent = false
            onConnection {
                if (gateFailure() == null && !sdkError.isCompleted) {
                    pending.set(result)
                    query()
                    sent = true
                }
            }
            return if (sent) await(result) else null
        } finally {
            pending.set(null)
        }
    }

    private suspend fun <T> await(result: CompletableDeferred<T>): T? =
        select {
            sdkError.onAwait { null }
            result.onAwait { it }
        }

    private fun gateFailure(): NavigationScreenResult? =
        when {
            !canDispatch() -> NavigationScreenResult.BLOCKED
            !hasPermissions() -> NavigationScreenResult.PERMISSION_DENIED
            else -> null
        }

    // The emulator can report ROUTE_GUIDING with isRouting=false after route calculation.
    private fun RouteStateInfo.isGuiding(): Boolean = termsAgreement && naviMode == NaviMode.ROUTE_GUIDING

    private fun PracticeNavigationRoute.toSdkRequest(): RouteWithWaypointsRequest =
        RouteWithWaypointsRequest(
            destination =
                RouteInfo(
                    latitude = destination.latitude,
                    longitude = destination.longitude,
                    poiName = destination.name,
                    poiId = destination.poiId,
                    address = destination.address,
                    poiSubId = destination.poiSubId,
                ),
            waypoints =
                waypoints.mapIndexed { index, point ->
                    RequestWaypointInfo(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        poiName = point.name,
                        waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                        poiId = point.poiId,
                        address = point.address,
                        poiSubId = point.poiSubId,
                    )
                },
        )
}
