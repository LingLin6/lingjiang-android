package com.linglin.lingjiang.network

import com.linglin.lingjiang.audio.AudioChunk

data class TranscriptionResult(
    val text: String,
    val confidence: Float? = null,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
)

interface CloudTranscriptionProvider {
    suspend fun transcribe(chunk: AudioChunk): Result<TranscriptionResult>
}

data class AnalysisRequest(
    val scenario: String,
    val currentText: String,
    val shortWindow: String,
    val rollingSummary: String,
    val structuredState: String,
    val force: Boolean,
)

data class AnalysisResult(
    val oneLineSummary: String,
    val rollingSummary: String,
    val insights: List<InsightDraft>,
    val actionItems: List<ActionDraft>,
    val risks: List<RiskDraft>,
    val decisions: List<DecisionDraft>,
    val researchQueries: List<String> = emptyList(),
)

data class InsightDraft(
    val priority: String,
    val category: String,
    val text: String,
    val evidence: String,
    val factType: String,
)

data class ActionDraft(
    val text: String,
    val owner: String,
    val dueAtText: String,
    val evidence: String,
)

data class RiskDraft(
    val text: String,
    val severity: String,
    val evidence: String,
    val factType: String,
)

data class DecisionDraft(
    val text: String,
    val evidence: String,
    val factType: String,
)

data class FinalReportRequest(
    val title: String,
    val scenario: String,
    val transcript: String,
    val rollingSummary: String,
    val insights: String,
    val actions: String,
    val risks: String,
    val decisions: String,
)

data class FinalReportDraft(
    val title: String,
    val overview: String,
    val markdown: String,
)

data class ResearchRequest(
    val scenario: String,
    val currentText: String,
    val shortWindow: String,
    val structuredState: String,
)

data class ResearchResult(
    val title: String,
    val summary: String,
    val queries: List<String>,
    val sources: List<ResearchSource>,
)

data class ResearchSource(
    val title: String,
    val url: String,
    val snippet: String,
)

interface LlmAnalysisProvider {
    suspend fun analyze(request: AnalysisRequest): Result<AnalysisResult>
    suspend fun research(request: ResearchRequest): Result<ResearchResult>
    suspend fun finalReport(request: FinalReportRequest): Result<FinalReportDraft>
}
