package com.linglin.lingjiang.session

import com.linglin.lingjiang.model.SessionStatus

data class LiveInsight(
    val priority: String,
    val category: String,
    val text: String,
    val evidence: String,
    val factType: String,
)

data class SessionUiState(
    val activeSessionId: String? = null,
    val status: SessionStatus = SessionStatus.Idle,
    val elapsedMillis: Long = 0L,
    val liveCaption: String = "",
    val currentSummary: String = "",
    val latestError: String? = null,
    val isAnalysisMuted: Boolean = false,
    val isAnalyzing: Boolean = false,
    val asrConfigured: Boolean = false,
    val asrPortable: Boolean = false,
    val asrModeLabel: String = "未配置",
    val llmConfigured: Boolean = false,
    val llmModeLabel: String = "未配置",
    val micRms: Double = 0.0,
    val micPeak: Int = 0,
    val micActiveRatio: Double = 0.0,
    val isSpeechDetected: Boolean = false,
    val transcriptionStatus: String = "待机",
    val latestInsights: List<LiveInsight> = emptyList(),
)
