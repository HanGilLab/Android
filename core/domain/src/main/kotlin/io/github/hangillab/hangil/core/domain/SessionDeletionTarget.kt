package io.github.hangillab.hangil.core.domain

/** A confirmation snapshot, not authority to delete; the repository must revalidate every field. */
data class SessionDeletionTarget(
    val session: PracticeSession,
    val observationCount: Long,
    val memoVersion: String?,
)
