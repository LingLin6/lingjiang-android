package com.linglin.lingjiang.data

import com.linglin.lingjiang.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SessionRepository(private val dao: SessionDao) {
    fun observeSessions(): Flow<List<SessionEntity>> = dao.observeSessions()

    fun observeSession(id: String): Flow<SessionEntity?> = dao.observeSession(id)

    fun observeTranscripts(id: String): Flow<List<TranscriptSegmentEntity>> = dao.observeTranscripts(id)

    fun observeInsights(id: String): Flow<List<RealtimeInsightEntity>> = dao.observeInsights(id)

    fun observeResearchTasks(id: String): Flow<List<ResearchTaskEntity>> = dao.observeResearchTasks(id)

    fun observeFinalReport(id: String): Flow<FinalReportEntity?> = dao.observeFinalReport(id)

    suspend fun createSession(scenario: String = "通用"): SessionEntity {
        val now = System.currentTimeMillis()
        val title = "会话 ${TITLE_FORMAT.format(Date(now))}"
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            startedAt = now,
            endedAt = null,
            durationMillis = 0L,
            scenario = scenario,
            status = SessionStatus.Listening.name,
            rollingSummary = "",
            oneLineSummary = "正在监听周边声音",
            finalReport = "",
            errorMessage = null,
        )
        dao.insertSession(session)
        return session
    }

    suspend fun updateStatus(sessionId: String, status: SessionStatus, error: String? = null) {
        val session = dao.getSession(sessionId) ?: return
        dao.updateSession(
            session.copy(
                status = status.name,
                durationMillis = currentDuration(session),
                errorMessage = error,
            ),
        )
    }

    suspend fun updateSummary(sessionId: String, rollingSummary: String, oneLineSummary: String) {
        val session = dao.getSession(sessionId) ?: return
        dao.updateSession(
            session.copy(
                rollingSummary = rollingSummary,
                oneLineSummary = oneLineSummary.ifBlank { session.oneLineSummary },
                durationMillis = currentDuration(session),
            ),
        )
    }

    suspend fun endSession(sessionId: String, finalReport: String): SessionEntity? {
        val session = dao.getSession(sessionId) ?: return null
        val now = System.currentTimeMillis()
        val ended = session.copy(
            endedAt = now,
            durationMillis = now - session.startedAt,
            status = SessionStatus.Reviewing.name,
            finalReport = finalReport,
            oneLineSummary = session.oneLineSummary.ifBlank { "会话已结束" },
        )
        dao.updateSession(ended)
        return ended
    }

    suspend fun insertTranscript(segment: TranscriptSegmentEntity) = dao.insertTranscript(segment)

    suspend fun recentTranscripts(sessionId: String, limit: Int = 24): List<TranscriptSegmentEntity> =
        dao.getRecentTranscripts(sessionId, limit).asReversed()

    suspend fun transcripts(sessionId: String): List<TranscriptSegmentEntity> = dao.getTranscripts(sessionId)

    suspend fun insights(sessionId: String): List<RealtimeInsightEntity> = dao.getInsights(sessionId)

    suspend fun researchTasks(sessionId: String): List<ResearchTaskEntity> = dao.getResearchTasks(sessionId)

    suspend fun saveResearchTask(task: ResearchTaskEntity) = dao.insertResearchTask(task)

    suspend fun actions(sessionId: String): List<ActionItemEntity> = dao.getActionItems(sessionId)

    suspend fun risks(sessionId: String): List<RiskEntity> = dao.getRisks(sessionId)

    suspend fun decisions(sessionId: String): List<DecisionEntity> = dao.getDecisions(sessionId)

    suspend fun saveAnalysis(
        sessionId: String,
        rollingSummary: String,
        oneLineSummary: String,
        insights: List<RealtimeInsightEntity>,
        actions: List<ActionItemEntity>,
        risks: List<RiskEntity>,
        decisions: List<DecisionEntity>,
    ) {
        val session = dao.getSession(sessionId) ?: return
        dao.saveAnalysis(
            session = session.copy(
                rollingSummary = rollingSummary,
                oneLineSummary = oneLineSummary.ifBlank { session.oneLineSummary },
                durationMillis = currentDuration(session),
                errorMessage = null,
            ),
            insights = insights,
            actions = actions,
            risks = risks,
            decisions = decisions,
        )
    }

    suspend fun saveFinalReport(report: FinalReportEntity) = dao.insertFinalReport(report)

    private fun currentDuration(session: SessionEntity): Long {
        val end = session.endedAt ?: System.currentTimeMillis()
        return end - session.startedAt
    }

    companion object {
        private val TITLE_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }
}
