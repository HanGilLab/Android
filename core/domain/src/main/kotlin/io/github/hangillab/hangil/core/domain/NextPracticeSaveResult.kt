package io.github.hangillab.hangil.core.domain

sealed interface NextPracticeSaveResult {
    data object Saved : NextPracticeSaveResult

    data object Blocked : NextPracticeSaveResult

    data object SourceChanged : NextPracticeSaveResult

    data object Unavailable : NextPracticeSaveResult
}
