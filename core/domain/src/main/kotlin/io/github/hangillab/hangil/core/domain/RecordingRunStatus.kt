package io.github.hangillab.hangil.core.domain

/** App-owned recording lifetime markers, not proof of continuous vehicle or location capture. */
enum class RecordingRunStatus {
    OPEN,
    STOPPED,
    FAILED,
    INTERRUPTED,
}
