package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration

/** Computed from typeof in the original SQLite row before Room numeric/string coercion. */
internal data class CourseCompletionDeclarationRow(
    @Embedded val entity: CourseCompletionDeclarationEntity,
    val validStorage: Int,
) {
    fun declaration(): CourseCompletionDeclaration {
        check(validStorage == 1) { "Invalid completion declaration storage type" }
        return entity.declaration()
    }
}
