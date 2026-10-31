package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.StateFlow

/** One recorder shared by the visible app and an explicitly enabled foreground service. */
interface RecordingController {
    val state: StateFlow<VehicleRecordingState>
    val background: StateFlow<BackgroundRecordingState>

    /** The Activity owns cancellation; an enabled service owns collection independently. */
    suspend fun recordWhileStarted()

    /** Retries only the ordinary visible-app recorder after a fresh live parking/UX check. */
    fun retry(): Boolean

    /** The supplied source and UI owner remain fixed until the native service acknowledges. */
    fun requestBackground(
        expected: PracticeSession,
        canStart: () -> Boolean,
    ): Boolean

    /** A safe stop request for exactly one run; never finishes the practice session. */
    fun stopBackground(expectedRunId: String): Boolean

    /** Called only after native foreground promotion; cancellation ends this service lifetime. */
    suspend fun recordInService(
        runId: String,
        sessionId: String,
    )

    /** Reports native startup failure only for the matching current run. */
    fun startFailed(runId: String)
}
