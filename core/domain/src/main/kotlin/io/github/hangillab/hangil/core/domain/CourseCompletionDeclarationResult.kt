package io.github.hangillab.hangil.core.domain

sealed interface CourseCompletionDeclarationResult {
    data class Saved(
        val declaration: CourseCompletionDeclaration,
    ) : CourseCompletionDeclarationResult

    data class AlreadyDeclared(
        val declaration: CourseCompletionDeclaration,
    ) : CourseCompletionDeclarationResult

    data object MissingSession : CourseCompletionDeclarationResult

    data object SourceChanged : CourseCompletionDeclarationResult

    data object ActiveSession : CourseCompletionDeclarationResult

    data object Blocked : CourseCompletionDeclarationResult

    data object InvalidTime : CourseCompletionDeclarationResult
}
