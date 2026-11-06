package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointInfo
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.PracticeNavigationRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PleosNavigationRouteRequestTest {
    @Test fun activeGuidanceOrSimulationNeverSendsAReplacement() =
        runTest {
            for (mode in listOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)) {
                val client = Client().apply { initialMode = mode }
                assertEquals(NavigationScreenResult.ACTIVE_ROUTE, client.listener.request(ROUTE, 100))
                assertNull(client.command)
            }
        }

    @Test fun parkOwnershipAndPermissionAreCheckedAgainAfterIdleReadback() =
        runTest {
            val hostLost = Client().apply { afterInitialRead = { allowed = false } }
            assertEquals(NavigationScreenResult.BLOCKED, hostLost.listener.request(ROUTE, 100))
            assertNull(hostLost.command)

            val permissionLost = Client().apply { afterInitialRead = { permission = false } }
            assertEquals(NavigationScreenResult.PERMISSION_DENIED, permissionLost.listener.request(ROUTE, 100))
            assertNull(permissionLost.command)
        }

    @Test fun confirmsOnlyMatchingActualGuidanceWithWaypointsInTheRequestedOrder() =
        runTest {
            val matched = Client().apply { idleReadbacksAfterDispatch = 1 }
            assertEquals(NavigationScreenResult.GUIDANCE_STARTED, matched.listener.request(ROUTE, 1_000))
            assertEquals(1, matched.sends)
            assertEquals(listOf(WaypointIndex.FIRST, WaypointIndex.SECOND), matched.command?.waypoints?.map { it.waypointIndex })

            val reversed = Client().apply { observedWaypoints = ROUTE.waypoints.reversed() }
            assertEquals(NavigationScreenResult.GUIDANCE_UNCONFIRMED, reversed.listener.request(ROUTE, 1_000))
            val simulated = Client().apply { subsequentMode = NaviMode.SIMULATION_GUIDING }
            assertEquals(NavigationScreenResult.GUIDANCE_UNCONFIRMED, simulated.listener.request(ROUTE, 1_000))
        }

    @Test fun missingProviderSubIdsMatchOnlyTheDefaultEntrance() =
        runTest {
            val default = Client().apply { omitSubIds = true }
            assertEquals(NavigationScreenResult.GUIDANCE_STARTED, default.listener.request(ROUTE, 1_000))

            val differentEntrance = ROUTE.copy(destination = ROUTE.destination.copy(poiSubId = "1"))
            val missing = Client().apply { omitSubIds = true }
            assertEquals(NavigationScreenResult.GUIDANCE_UNCONFIRMED, missing.listener.request(differentEntrance, 1_000))
        }

    @Test fun guidanceThatNeverStartsStaysUnconfirmedWithoutAnotherSend() =
        runTest {
            val client = Client().apply { idleReadbacksAfterDispatch = Int.MAX_VALUE }
            assertEquals(NavigationScreenResult.GUIDANCE_UNCONFIRMED, client.listener.request(ROUTE, 600))
            assertEquals(3, client.stateReads)
            assertEquals(1, client.sends)
        }

    private class Client {
        var allowed = true
        var permission = true
        var initialMode = NaviMode.SAFE_GUIDING
        var subsequentMode = NaviMode.ROUTE_GUIDING
        var idleReadbacksAfterDispatch = 0
        var omitSubIds = false
        var observedWaypoints = ROUTE.waypoints
        var afterInitialRead: () -> Unit = {}
        var command: RouteWithWaypointsRequest? = null
        var stateReads = 0
        var sends = 0
        val listener: PleosNavigationRouteRequest =
            PleosNavigationRouteRequest(
                onConnection = { action ->
                    action()
                    true
                },
                hasPermissions = { permission },
                canDispatch = { allowed },
                getRouteState = ::readState,
                getDestination = ::readDestination,
                getWaypoints = ::readWaypoints,
                requestRoute = ::sendRoute,
            )

        private fun readState() {
            stateReads++
            val afterDispatch = command != null
            val awaitingGuidance = afterDispatch && idleReadbacksAfterDispatch > 0
            if (awaitingGuidance) idleReadbacksAfterDispatch--
            listener.onRouteStateInfo(
                RouteStateInfo(
                    termsAgreement = true,
                    naviMode = if (afterDispatch && !awaitingGuidance) subsequentMode else initialMode,
                    isRouting = false,
                    routeOption = RouteOption.RECOMMENDED,
                    remainDestinationChargingInt = 0,
                    remainWaypointChargingInt = emptyList(),
                    trafficStatus = null,
                ),
            )
            if (command == null) afterInitialRead()
        }

        private fun readDestination() {
            val point = ROUTE.destination
            listener.onDestinationInfo(
                DestinationInfo(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    name = point.name,
                    address = point.address,
                    remainDistance = 1000,
                    remainTime = 180,
                    tollFare = 0,
                    poiId = point.poiId,
                    poiSubId = point.poiSubId.takeUnless { omitSubIds },
                ),
            )
        }

        private fun readWaypoints() {
            listener.onWayPointInfo(
                observedWaypoints.map { point ->
                    WaypointInfo(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        name = point.name,
                        address = point.address,
                        remainDistance = 500,
                        remainTime = 80,
                        poiId = point.poiId,
                        poiSubId = point.poiSubId.takeUnless { omitSubIds },
                    )
                },
            )
        }

        private fun sendRoute(request: RouteWithWaypointsRequest) {
            sends++
            command = request
        }
    }

    private companion object {
        val ROUTE =
            PracticeNavigationRoute(
                destination = PracticeNavigationRoute.Point("Destination", 1.0, 1.0, "destination", "0", "Address"),
                waypoints =
                    listOf(
                        PracticeNavigationRoute.Point("First", 2.0, 2.0, "first", "0", "Address"),
                        PracticeNavigationRoute.Point("Second", 3.0, 3.0, "second", "0", "Address"),
                    ),
            )
    }
}
