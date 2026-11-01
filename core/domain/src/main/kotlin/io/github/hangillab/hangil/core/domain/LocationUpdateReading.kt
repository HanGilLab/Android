package io.github.hangillab.hangil.core.domain

/** App receipt/decision time is never an interpreted SDK timestamp or proof of a current fix. */
sealed interface LocationUpdateReading {
    val receivedAtElapsedRealtimeMillis: Long

    data class Sample(
        val sample: LocationUpdateSample,
    ) : LocationUpdateReading {
        override val receivedAtElapsedRealtimeMillis: Long get() = sample.receivedAtElapsedRealtimeMillis
    }

    data class Empty(
        override val receivedAtElapsedRealtimeMillis: Long,
    ) : LocationUpdateReading {
        init {
            require(receivedAtElapsedRealtimeMillis >= 0)
        }
    }

    data class PermissionDenied(
        override val receivedAtElapsedRealtimeMillis: Long,
    ) : LocationUpdateReading {
        init {
            require(receivedAtElapsedRealtimeMillis >= 0)
        }
    }

    data class Unsupported(
        override val receivedAtElapsedRealtimeMillis: Long,
    ) : LocationUpdateReading {
        init {
            require(receivedAtElapsedRealtimeMillis >= 0)
        }
    }

    data class NoUpdate(
        override val receivedAtElapsedRealtimeMillis: Long,
    ) : LocationUpdateReading {
        init {
            require(receivedAtElapsedRealtimeMillis >= 0)
        }
    }

    data class Error(
        val origin: LocationUpdateErrorOrigin,
        val exceptionClassName: String,
        override val receivedAtElapsedRealtimeMillis: Long,
    ) : LocationUpdateReading {
        init {
            require(receivedAtElapsedRealtimeMillis >= 0)
            require(exceptionClassName.isNotBlank() && exceptionClassName.none { it.isWhitespace() }) { "Invalid error class name" }
        }
    }
}
