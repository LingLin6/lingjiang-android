package com.linglin.lingjiang.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscript(segment: TranscriptSegmentEntity)

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startMillis ASC, createdAt ASC")
    suspend fun getTranscripts(sessionId: String): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startMillis ASC, createdAt ASC")
    fun observeTranscripts(sessionId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentTranscripts(sessionId: String, limit: Int): List<TranscriptSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInsight(insight: RealtimeInsightEntity)

    @Query("SELECT * FROM realtime_insights WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeInsights(sessionId: String): Flow<List<RealtimeInsightEntity>>

    @Query("SELECT * FROM realtime_insights WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getInsights(sessionId: String): List<RealtimeInsightEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResearchTask(task: ResearchTaskEntity)

    @Query("SELECT * FROM research_tasks WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeResearchTasks(sessionId: String): Flow<List<ResearchTaskEntity>>

    @Query("SELECT * FROM research_tasks WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getResearchTasks(sessionId: String): List<ResearchTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionItem(actionItem: ActionItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRisk(risk: RiskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecision(decision: DecisionEntity)

    @Query("SELECT * FROM action_items WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getActionItems(sessionId: String): List<ActionItemEntity>

    @Query("SELECT * FROM risks WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getRisks(sessionId: String): List<RiskEntity>

    @Query("SELECT * FROM decisions WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getDecisions(sessionId: String): List<DecisionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFinalReport(report: FinalReportEntity)

    @Query("SELECT * FROM final_reports WHERE sessionId = :sessionId")
    fun observeFinalReport(sessionId: String): Flow<FinalReportEntity?>

    @Query("SELECT * FROM final_reports WHERE sessionId = :sessionId")
    suspend fun getFinalReport(sessionId: String): FinalReportEntity?

    @Transaction
    suspend fun saveAnalysis(
        session: SessionEntity,
        insights: List<RealtimeInsightEntity>,
        actions: List<ActionItemEntity>,
        risks: List<RiskEntity>,
        decisions: List<DecisionEntity>,
    ) {
        updateSession(session)
        insights.forEach { insertInsight(it) }
        actions.forEach { insertActionItem(it) }
        risks.forEach { insertRisk(it) }
        decisions.forEach { insertDecision(it) }
    }
}
