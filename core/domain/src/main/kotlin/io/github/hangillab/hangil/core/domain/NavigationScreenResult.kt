package io.github.hangillab.hangil.core.domain

/** REQUESTED means an SDK call, never confirmed foreground, routing, or guidance success. */
enum class NavigationScreenResult { REQUESTED, BLOCKED, UNSUPPORTED, PERMISSION_DENIED, TIMED_OUT, ERROR }
