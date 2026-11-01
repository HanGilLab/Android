package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.StateFlow

/** An explicit location owner exists only while the actual Main STARTED host remains alive. */
interface LocationRecordingController {
    val state: StateFlow<LocationRecordingState>

    /** Cancellation closes the sink, joins pending work, and ends only its original durable owner. */
    suspend fun recordWhileStarted()

    /** The supplied full source and fresh RESUMED/P+UX receipt are fixed for this request. */
    fun start(
        expected: PracticeSession,
        canStart: () -> Boolean,
    ): Boolean

    /** Explicit cleanup for this exact run, never an automatic restart or session finish. */
    fun stop(expectedRunId: String): Boolean
}
