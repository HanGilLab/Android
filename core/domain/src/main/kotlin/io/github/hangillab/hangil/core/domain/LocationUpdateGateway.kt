package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

/** A cold SDK update stream, separate from LastKnown and never an automatic background collector. */
interface LocationUpdateGateway {
    /** A fresh framework permission read; exceptions are not permission-denied success values. */
    fun hasPermission(): Boolean

    /**
     * Sample/real Empty are nonterminal. Denied/Unsupported/NoUpdate/Error terminate after cleanup.
     * Owner denial/cancellation revokes delivery without manufacturing an SDK event.
     */
    fun updates(canContinue: () -> Boolean): Flow<LocationUpdateReading> = updates(canContinue, {}, {})

    fun updates(
        canContinue: () -> Boolean,
        onRegistered: () -> Unit,
    ): Flow<LocationUpdateReading> = updates(canContinue, onRegistered, {})

    /**
     * onRegistered is only the local normal register return, never a remote acknowledgement.
     * onCleanupFailure is private diagnostic Throwable delivery, completed before collection
     * finishes/cancelAndJoin returns; its message must never enter UI or public logs.
     */
    fun updates(
        canContinue: () -> Boolean,
        onRegistered: () -> Unit,
        onCleanupFailure: (Throwable) -> Unit,
    ): Flow<LocationUpdateReading>

    companion object {
        const val NO_UPDATE_TIMEOUT_MILLIS = 30_000L
    }
}
