package io.github.hangillab.hangil.core.domain

/** REQUESTED means screen dispatch. GUIDANCE_STARTED means matching active guidance was observed. */
enum class NavigationScreenResult {
    REQUESTED,
    BLOCKED,
    UNSUPPORTED,
    PERMISSION_DENIED,
    TIMED_OUT,
    ERROR,
    GUIDANCE_STARTED,
    GUIDANCE_UNCONFIRMED,
    ACTIVE_ROUTE,
}
