package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * Sequentially appends states delivered by VehicleGateway to one fixed session.
 * StateFlow may omit intermediate or equal states, including while an append is suspended.
 * The supplied clock marks the application's observation, not an SDK event or value receive time.
 * Individual readings and their unknown, unsupported, denied or failed states are not transformed.
 *
 * Only one call may run on this instance. The caller must cancel it when the session or its
 * collection lifecycle ends, even if no new vehicle state arrives to trigger a session rejection.
 * This class does not start a background service, switch sessions or retry failed writes.
 */
class VehicleObservationRecorder(
    private val gateway: VehicleGateway,
    private val repository: VehicleObservationRepository,
    private val nowEpochMillis: () -> Long,
) {
    private val running = Mutex()

    /** Cancellation and failures outside repository.append propagate to the caller unchanged. */
    suspend fun record(sessionId: String): VehicleObservationRecordingResult {
        if (!running.tryLock()) return VehicleObservationRecordingResult.AlreadyRunning
        try {
            var appendedCount = 0L
            var stopped: VehicleObservationRecordingResult? = null
            gateway.state.first { state ->
                val observation = VehicleObservation(nowEpochMillis(), state)
                val result =
                    try {
                        repository.append(sessionId, observation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        stopped = VehicleObservationRecordingResult.WriteFailed(appendedCount, failure)
                        return@first true
                    }
                stopped =
                    when (result) {
                        is VehicleObservationAppendResult.Appended -> {
                            appendedCount++
                            null
                        }
                        VehicleObservationAppendResult.MissingSession -> VehicleObservationRecordingResult.MissingSession(appendedCount)
                        VehicleObservationAppendResult.FinishedSession -> VehicleObservationRecordingResult.FinishedSession(appendedCount)
                        VehicleObservationAppendResult.InvalidTime -> VehicleObservationRecordingResult.InvalidTime(appendedCount)
                    }
                stopped != null
            }
            return checkNotNull(stopped)
        } finally {
            running.unlock()
        }
    }
}
