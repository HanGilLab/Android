package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession

/** A received repository acknowledgement, not an inferred completion or an automatic navigation. */
class SessionFinishReceipt internal constructor(
    val source: PracticeSession,
    val kind: Kind,
    internal val nonce: Long,
) {
    enum class Kind { FINISHED, ALREADY_FINISHED }
}
