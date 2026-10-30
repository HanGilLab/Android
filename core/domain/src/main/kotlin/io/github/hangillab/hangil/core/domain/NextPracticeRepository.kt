package io.github.hangillab.hangil.core.domain

/** Saves a next preparation anchored to the latest ended session, without starting a session. */
interface NextPracticeRepository {
    /** Revalidate the live permission, source and complete catalog seed before and after the write. */
    suspend fun saveSelection(
        seed: NextPracticeSeed,
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): NextPracticeSaveResult
}
