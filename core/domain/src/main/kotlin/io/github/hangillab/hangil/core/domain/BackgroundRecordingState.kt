package io.github.hangillab.hangil.core.domain

/** Service ownership and interruption evidence, independently of individual SDK reading states. */
sealed interface BackgroundRecordingState {
    data object Disabled : BackgroundRecordingState

    data class Starting(
        val runId: String,
        val sessionId: String,
    ) : BackgroundRecordingState

    data class Active(
        val runId: String,
        val sessionId: String,
    ) : BackgroundRecordingState

    data class Stopping(
        val runId: String,
        val sessionId: String,
    ) : BackgroundRecordingState

    data class Failed(
        val runId: String?,
        val sessionId: String?,
        val reason: Reason,
    ) : BackgroundRecordingState

    /** An earlier process left an open marker for this still-active session. */
    data class Interrupted(
        val sessionId: String,
    ) : BackgroundRecordingState

    enum class Reason {
        RECOVERY,
        SOURCE_CHANGED,
        ALREADY_OPEN,
        BLOCKED,
        INVALID_TIME,
        OPEN,
        SERVICE_START,
        SERVICE_STOP,
        SESSION_READ,
        OBSERVATION,
        PERSISTENCE,

        /** A stored FAILED run does not preserve the original failure cause. */
        PREVIOUS_RUN,
    }
}
