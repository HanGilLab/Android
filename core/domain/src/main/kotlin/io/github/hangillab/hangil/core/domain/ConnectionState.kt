package io.github.hangillab.hangil.core.domain

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    UNAVAILABLE,
    PERMISSION_DENIED,
    ERROR,
}
