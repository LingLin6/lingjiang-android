package com.linglin.lingjiang.model

enum class SessionStatus {
    Idle,
    Listening,
    Analyzing,
    Alerting,
    Paused,
    Reviewing,
    Error,
}

enum class InsightPriority {
    Low,
    Medium,
    High,
}

enum class InsightCategory {
    Suggestion,
    Risk,
    Question,
    Action,
    Decision,
    Summary,
    Intent,
    Research,
    Error,
}

enum class TranscriptSource {
    SystemCaption,
    CloudAsr,
    OnDeviceAsr,
}
