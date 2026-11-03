package io.github.hangillab.hangil.core.domain

enum class LocationRecordingStopReason {
    USER_STOP,
    HOST_STOPPED,
    SESSION_FINISHED,
    SOURCE_CHANGED,
    PERMISSION_DENIED,
    UNSUPPORTED,
    NO_UPDATE,
    SDK_ERROR,
    ADAPTER_ERROR,
    WRITE_ERROR,
    LIMIT_REACHED,
    PROCESS_INTERRUPTED,
    ;

    val status: LocationRecordingRunStatus
        get() =
            when (this) {
                USER_STOP, HOST_STOPPED, SESSION_FINISHED, LIMIT_REACHED -> LocationRecordingRunStatus.STOPPED
                PROCESS_INTERRUPTED -> LocationRecordingRunStatus.INTERRUPTED
                else -> LocationRecordingRunStatus.FAILED
            }
}
