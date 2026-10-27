package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout

/** SDK가 연결 콜백을 공개하지 않으므로 현재 기어 조회로 연결 생존 여부를 확인한다. */
internal class VehicleMonitor(
    private val createClient: () -> VehicleClient,
    private val readTimeoutMillis: Long = 3_000,
    private val pollIntervalMillis: Long = 1_000,
    private val retryDelayMillis: Long = 2_000,
) {
    fun observe(): Flow<VehicleState> =
        channelFlow {
            while (isActive) {
                val session = Session(this)
                var client: VehicleClient? = null
                var terminal = false
                try {
                    client = createClient()
                    client.open(session)
                    while (isActive) {
                        session.checkFailure()
                        val revision = session.gearRevision()
                        val gear = withTimeout(readTimeoutMillis) { client.readGear() }
                        session.checkFailure()
                        when (gear) {
                            VehicleReading.PermissionDenied -> throw SecurityException()
                            VehicleReading.Unsupported -> throw UnsupportedOperationException()
                            VehicleReading.Error, VehicleReading.Unknown -> throw VehicleReadException()
                            is VehicleReading.Available -> session.onPolledGear(gear, revision)
                        }
                        delay(pollIntervalMillis)
                    }
                } catch (failure: SessionFailure) {
                    terminal = failure.connection == ConnectionState.PERMISSION_DENIED || failure.connection == ConnectionState.UNAVAILABLE
                } catch (_: TimeoutCancellationException) {
                    session.fail(ConnectionState.DISCONNECTED)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    session.fail(ConnectionState.PERMISSION_DENIED)
                    terminal = true
                } catch (_: UnsupportedOperationException) {
                    session.fail(ConnectionState.UNAVAILABLE)
                    terminal = true
                } catch (_: Exception) {
                    session.fail(ConnectionState.ERROR)
                } finally {
                    // 먼저 이전 세대의 콜백을 차단하고 SDK 리스너와 바인딩을 해제한다.
                    session.close()
                    runCatching { client?.close() }
                }
                if (terminal) awaitCancellation()
                delay(retryDelayMillis)
            }
        }.buffer(Channel.CONFLATED)

    private class VehicleReadException : Exception()

    private class SessionFailure(
        val connection: ConnectionState,
    ) : Exception()

    private class Session(
        private val output: ProducerScope<VehicleState>,
    ) : VehicleClient.Observer {
        private var active = true
        private var revision = 0L
        private var failure: ConnectionState? = null
        private var state = VehicleState(connection = ConnectionState.CONNECTING)

        init {
            output.trySend(state)
        }

        @Synchronized
        fun gearRevision(): Long = revision

        @Synchronized
        fun checkFailure() {
            failure?.let { throw SessionFailure(it) }
        }

        @Synchronized
        override fun onGear(value: VehicleReading<GearPosition>) {
            revision++
            if (value is VehicleReading.Available) {
                publish(state.copy(gear = value))
            } else {
                fail(
                    when (value) {
                        VehicleReading.PermissionDenied -> ConnectionState.PERMISSION_DENIED
                        VehicleReading.Unsupported -> ConnectionState.UNAVAILABLE
                        VehicleReading.Error -> ConnectionState.ERROR
                        else -> ConnectionState.CONNECTING
                    },
                )
            }
        }

        @Synchronized
        fun onPolledGear(
            value: VehicleReading<GearPosition>,
            requestRevision: Long,
        ) {
            // 조회 중 도착한 더 최신 기어 콜백을 이전 조회 결과로 덮어쓰지 않는다.
            if (revision == requestRevision) publish(state.copy(gear = value))
        }

        @Synchronized
        override fun onTurnSignal(value: VehicleReading<TurnSignalState>) {
            if (value == VehicleReading.PermissionDenied) {
                fail(ConnectionState.PERMISSION_DENIED)
            } else {
                publish(state.copy(turnSignal = value))
            }
        }

        @Synchronized
        override fun onBrake(value: VehicleReading<Boolean>) {
            if (value == VehicleReading.PermissionDenied) {
                fail(ConnectionState.PERMISSION_DENIED)
            } else {
                publish(state.copy(brakePressed = value))
            }
        }

        private fun publish(next: VehicleState) {
            if (!active) return
            val readings = listOf(next.gear, next.turnSignal, next.brakePressed)
            val connection =
                when {
                    VehicleReading.PermissionDenied in readings -> ConnectionState.PERMISSION_DENIED
                    next.gear is VehicleReading.Available -> ConnectionState.CONNECTED
                    next.gear == VehicleReading.Unsupported -> ConnectionState.UNAVAILABLE
                    next.gear == VehicleReading.Error -> ConnectionState.ERROR
                    else -> ConnectionState.CONNECTING
                }
            state = next.copy(connection = connection)
            output.trySend(state)
        }

        @Synchronized
        fun fail(connection: ConnectionState) {
            if (!active) return
            active = false
            failure = connection
            state = VehicleState(connection = connection)
            output.trySend(state)
        }

        @Synchronized
        fun close() {
            active = false
        }
    }
}
