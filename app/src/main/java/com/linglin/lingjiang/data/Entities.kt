package com.linglin.lingjiang.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMillis: Long,
    val scenario: String,
    val status: String,
    val rollingSummary: String,
    val oneLineSummary: String,
    val finalReport: String,
    val errorMessage: String?,
)

@Entity(
    tableName = "transcript_segments",
    indices = [Index("sessionId"), Index("createdAt")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val startMillis: Long,
    val endMillis: Long,
    val text: String,
    val source: String,
    val confidence: Float?,
    val isFinal: Boolean,
    val createdAt: Long,
    val speakerLabel: String = "说话人待确认",
    val speakerConfidence: Float? = null,
    val turnIndex: Int = 0,
    val intentLabel: String = "未判断",
    val intentConfidence: Float? = null,
    val intentEvidence: String = "",
)

@Entity(
    tableName = "realtime_insights",
    indices = [Index("sessionId"), Index("createdAt")],
)
data class RealtimeInsightEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val priority: String,
    val category: String,
    val text: String,
    val evidence: String,
    val spoken: Boolean,
    val factType: String,
)

@Entity(
    tableName = "research_tasks",
    indices = [Index("sessionId"), Index("createdAt")],
)
data class ResearchTaskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val query: String,
    val status: String,
    val title: String,
    val summary: String,
    val sources: String,
    val errorMessage: String?,
)

@Entity(tableName = "action_items", indices = [Index("sessionId")])
data class ActionItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val text: String,
    val owner: String,
    val dueAtText: String,
    val evidence: String,
    val completed: Boolean,
)

@Entity(tableName = "risks", indices = [Index("sessionId")])
data class RiskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val text: String,
    val severity: String,
    val evidence: String,
    val factType: String,
)

@Entity(tableName = "decisions", indices = [Index("sessionId")])
data class DecisionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val text: String,
    val evidence: String,
    val factType: String,
)

@Entity(tableName = "final_reports")
data class FinalReportEntity(
    @PrimaryKey val sessionId: String,
    val generatedAt: Long,
    val title: String,
    val overview: String,
    val contentMarkdown: String,
)
