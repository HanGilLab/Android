package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.SessionMemo

/** Inspect original SQLite types before Room's String decoding can hide malformed storage. */
internal data class SessionMemoRow(
    @Embedded val entity: SessionMemoEntity,
    val sessionStorageType: String,
    val textStorageType: String,
    val versionStorageType: String,
) {
    fun memo(): SessionMemo {
        check(sessionStorageType == "text" && textStorageType == "text" && versionStorageType == "text") {
            "Invalid stored user memo storage type"
        }
        return entity.memo()
    }
}
