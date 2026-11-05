package io.github.hangillab.hangil.core.domain

/** Saves a next preparation without starting a session. */
interface NextPracticeRepository {
    /** Revalidate the latest ended source, live permission and complete catalog seed before and after the write. */
    suspend fun saveSelection(
        seed: NextPracticeSeed,
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): NextPracticeSaveResult

    /**
     * Revalidate the exact selected ended source and complete catalog seed before and after the write.
     * A newer ended record does not replace the selected source. Any unfinished session still blocks
     * the write, and the caller's live permission must remain valid through the transaction.
     */
    suspend fun saveHistorySelection(
        seed: NextPracticeSeed,
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): NextPracticeSaveResult
}
