package io.github.hangillab.hangil

import javax.inject.Inject
import javax.inject.Singleton

/** Stops only the native lifetime that registered this exact controller run. */
@Singleton
class VehicleRecordingServiceHost
    @Inject
    constructor() {
        private val lock = Any()
        private var owner: Pair<String, () -> Unit>? = null

        fun attach(
            runId: String,
            stop: () -> Unit,
        ) = synchronized(lock) {
            check(owner == null) { "A recording service lifetime already exists" }
            owner = runId to stop
        }

        fun stop(runId: String) {
            val action = synchronized(lock) { owner?.takeIf { it.first == runId }?.second }
            action?.invoke()
        }

        fun detach(runId: String): Boolean =
            synchronized(lock) {
                if (owner?.first != runId) return@synchronized false
                owner = null
                true
            }
    }
