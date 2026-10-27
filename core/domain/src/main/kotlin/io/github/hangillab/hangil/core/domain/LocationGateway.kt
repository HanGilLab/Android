package io.github.hangillab.hangil.core.domain

interface LocationGateway {
    /** Reads an existing SDK sample once; this does not request or guarantee a new current position. */
    suspend fun readLastKnown(): LocationReading
}
