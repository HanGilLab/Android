package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import java.util.UUID

@Entity(
    tableName = "session_memo",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SessionMemoEntity(
    @PrimaryKey val sessionId: String,
    val text: String,
    val version: String,
) {
    fun memo(): SessionMemo {
        check(SessionMemoPolicy.isValidText(text)) { "Invalid stored user memo text" }
        check(runCatching { UUID.fromString(version).toString() == version }.getOrDefault(false)) { "Invalid stored user memo version" }
        return SessionMemo(sessionId, text, version)
    }
}
