package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession

/** An opaque, one-use UI receipt. The source is never replaced by the latest session. */
class SessionFinishAction internal constructor(
    val source: PracticeSession,
    internal val nonce: Long,
)
