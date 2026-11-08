package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot

@Entity(
    tableName = "session_review_road_node",
    primaryKeys = ["sessionId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = SessionReviewRoadEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SessionReviewRoadNodeEntity(
    val sessionId: String,
    val ordinal: Int,
    val osmNodeId: Long,
    val osmNodeVersion: Int,
    val latitude: Double,
    val longitude: Double,
) {
    fun node() = CourseRoadSnapshot.Node(osmNodeId, osmNodeVersion, latitude, longitude)
}
