package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val REVIEW_ROAD_SELECT =
    "SELECT r.*, n.sessionId AS node_sessionId, n.ordinal AS node_ordinal, n.osmNodeId AS node_osmNodeId, " +
        "n.osmNodeVersion AS node_osmNodeVersion, n.latitude AS node_latitude, n.longitude AS node_longitude, " +
        "CASE WHEN typeof(r.sessionId) = 'text' AND typeof(r.goalId) = 'text' AND typeof(r.courseId) = 'text' " +
        "AND typeof(r.taskId) = 'text' AND typeof(r.goalTitle) = 'text' AND typeof(r.courseTitle) = 'text' " +
        "AND typeof(r.taskTitle) = 'text' AND typeof(r.courseReviewStatus) = 'text' AND typeof(r.version) = 'text' " +
        "AND typeof(r.origin) = 'text' AND typeof(r.roadId) = 'text' AND typeof(r.roadTitle) = 'text' " +
        "AND typeof(r.highway) = 'text' AND typeof(r.courseVersion) = 'integer' AND r.courseVersion BETWEEN 1 AND 2147483647 " +
        "AND typeof(r.briefingVersion) = 'integer' AND r.briefingVersion BETWEEN 1 AND 2147483647 " +
        "AND typeof(r.startedAtEpochMillis) = 'integer' AND typeof(r.endedAtEpochMillis) = 'integer' " +
        "AND typeof(r.osmWayId) = 'integer' AND typeof(r.osmWayVersion) = 'integer' " +
        "AND r.osmWayVersion BETWEEN 1 AND 2147483647 AND typeof(r.capturedAtEpochMillis) = 'integer' " +
        "AND typeof(r.nodeCount) = 'integer' AND r.nodeCount BETWEEN 2 AND 2147483647 THEN 1 ELSE 0 END AS validStorage, " +
        "CASE WHEN typeof(n.sessionId) = 'text' AND typeof(n.ordinal) = 'integer' AND n.ordinal BETWEEN 0 AND 2147483647 " +
        "AND typeof(n.osmNodeId) = 'integer' AND typeof(n.osmNodeVersion) = 'integer' " +
        "AND n.osmNodeVersion BETWEEN 1 AND 2147483647 AND typeof(n.latitude) = 'real' " +
        "AND typeof(n.longitude) = 'real' THEN 1 ELSE 0 END AS nodeValidStorage " +
        "FROM session_review_road r LEFT JOIN session_review_road_node n ON n.sessionId = r.sessionId " +
        "WHERE r.sessionId = :sessionId ORDER BY n.ordinal ASC"

@Dao
internal interface SessionReviewRoadDao {
    @Query(REVIEW_ROAD_SELECT)
    fun observe(sessionId: String): Flow<List<SessionReviewRoadRow>>

    @Query(REVIEW_ROAD_SELECT)
    suspend fun find(sessionId: String): List<SessionReviewRoadRow>

    @Insert
    suspend fun insert(entity: SessionReviewRoadEntity)

    @Insert
    suspend fun insertNodes(entities: List<SessionReviewRoadNodeEntity>)

    @Query(
        "UPDATE session_review_road SET version = :version, roadId = :roadId, roadTitle = :title, highway = :highway, " +
            "osmWayId = :wayId, osmWayVersion = :wayVersion, capturedAtEpochMillis = :capturedAt, nodeCount = :nodeCount " +
            "WHERE sessionId = :sessionId AND version = :expectedVersion",
    )
    suspend fun update(
        sessionId: String,
        expectedVersion: String,
        version: String,
        roadId: String,
        title: String,
        highway: String,
        wayId: Long,
        wayVersion: Int,
        capturedAt: Long,
        nodeCount: Int,
    ): Int

    @Query("DELETE FROM session_review_road_node WHERE sessionId = :sessionId")
    suspend fun deleteNodes(sessionId: String): Int

    @Query("DELETE FROM session_review_road WHERE sessionId = :sessionId AND version = :expectedVersion")
    suspend fun delete(
        sessionId: String,
        expectedVersion: String,
    ): Int
}
