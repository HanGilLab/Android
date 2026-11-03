package io.github.hangillab.hangil.core.domain

/** Ownership/marker state, never proof of position accuracy, continuity, or remote SDK stop. */
sealed interface LocationRecordingState {
    data object Paused : LocationRecordingState

    data object Idle : LocationRecordingState

    data class Starting(
        val runId: String,
        val source: PracticeSession,
    ) : LocationRecordingState {
        override fun toString(): String = "Starting(owner=redacted, source=redacted)"
    }

    data class Recording(
        val run: LocationRecordingRun,
    ) : LocationRecordingState

    data class Stopping(
        val runId: String,
        val source: PracticeSession,
        val run: LocationRecordingRun?,
        val reason: LocationRecordingStopReason,
    ) : LocationRecordingState {
        override fun toString(): String = "Stopping(owner=redacted, source=redacted, reason=$reason)"
    }

    data class Stopped(
        val run: LocationRecordingRun,
        val reason: LocationRecordingStopReason,
    ) : LocationRecordingState

    data class Failed(
        val source: PracticeSession?,
        val runId: String?,
        val reason: Reason,
        val storageUnknown: Boolean = false,
        val exceptionClassName: String? = null,
    ) : LocationRecordingState {
        override fun toString(): String = "Failed(owner=redacted, source=redacted, reason=$reason, storageUnknown=$storageUnknown)"
    }

    enum class Reason {
        SOURCE_READ,
        SOURCE_CHANGED,
        SESSION_FINISHED,
        BLOCKED,
        PERMISSION_CHECK,
        PERMISSION_DENIED,
        UNSUPPORTED,
        NO_UPDATE,
        SDK_ERROR,
        ADAPTER_ERROR,
        OPEN,
        WRITE,
        END,
        RECOVERY,
        INVALID_TIME,
        ALREADY_OPEN,
    }
}
