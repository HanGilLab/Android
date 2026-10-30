package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy

/** Saved text, the editor draft and the pending write are deliberately independent. */
data class SessionMemoUiState(
    val sessionId: String,
    val load: Load = Load.Loading,
    val memo: SessionMemo? = null,
    val draft: String = "",
    val editing: Boolean = false,
    val expectedVersion: String? = null,
    val operation: Operation = Operation.Idle,
    val feedback: Feedback? = null,
    val needsReload: Boolean = false,
    val failure: FailureReason? = null,
) {
    val canEdit get() = load == Load.Ready && operation == Operation.Idle
    val canSave get() = canEdit && editing && !needsReload && SessionMemoPolicy.isValidText(draft)
    val canDelete get() = canEdit && memo != null && !needsReload

    enum class Load { Loading, Locked, Ready, SessionChanged, Failed }

    enum class Operation { Idle, Saving, Deleting }

    enum class Feedback {
        Saved,
        Deleted,
        Blocked,
        SessionChanged,
        MissingSession,
        SessionActive,
        Conflict,
        InvalidText,
        SaveFailed,
        DeleteFailed,
    }

    enum class FailureReason {
        AccessRead,
        AccessStreamCompleted,
        SessionRead,
        SessionStreamCompleted,
        MemoRead,
        MemoStreamCompleted,
        InvalidMemo,
    }
}
