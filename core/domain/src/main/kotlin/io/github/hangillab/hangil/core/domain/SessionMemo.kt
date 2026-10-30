package io.github.hangillab.hangil.core.domain

/** A user's own note, separate from SDK observations. Version is an opaque nonblank change token. */
data class SessionMemo(
    val sessionId: String,
    val text: String,
    val version: String,
)
