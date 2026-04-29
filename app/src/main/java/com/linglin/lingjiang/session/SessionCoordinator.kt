package com.linglin.lingjiang.session

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.linglin.lingjiang.BuildConfig
import com.linglin.lingjiang.audio.AudioSegmentRecorder
import com.linglin.lingjiang.audio.AudioChunk
import com.linglin.lingjiang.audio.CaptionUpdate
import com.linglin.lingjiang.audio.RealtimeCaptionProvider
import com.linglin.lingjiang.data.ActionItemEntity
import com.linglin.lingjiang.data.DecisionEntity
import com.linglin.lingjiang.data.FinalReportEntity
import com.linglin.lingjiang.data.RealtimeInsightEntity
import com.linglin.lingjiang.data.ResearchTaskEntity
import com.linglin.lingjiang.data.RiskEntity
import com.linglin.lingjiang.data.SessionRepository
import com.linglin.lingjiang.data.TranscriptSegmentEntity
import com.linglin.lingjiang.model.InsightCategory
import com.linglin.lingjiang.model.InsightPriority
import com.linglin.lingjiang.model.SessionStatus
import com.linglin.lingjiang.model.TranscriptSource
import com.linglin.lingjiang.network.ApiConfig
import com.linglin.lingjiang.network.AnalysisRequest
import com.linglin.lingjiang.network.AnalysisResult
import com.linglin.lingjiang.network.CloudTranscriptionProvider
import com.linglin.lingjiang.network.FinalReportRequest
import com.linglin.lingjiang.network.LlmAnalysisProvider
import com.linglin.lingjiang.network.ResearchRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SessionCoordinator(
    private val appContext: Context,
    private val repository: SessionRepository,
    private val captionProvider: RealtimeCaptionProvider,
    private val recorder: AudioSegmentRecorder,
    private val transcriptionProvider: CloudTranscriptionProvider,
    private val analysisProvider: LlmAnalysisProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val apiConfig = ApiConfig.fromBuildConfig()
    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var captionJob: Job? = null
    private var tickerJob: Job? = null
    private val transcriptionMutex = Mutex()
    private val analysisMutex = Mutex()
    private val researchMutex = Mutex()
    private val speakerAttributor = SpeakerAttributor()
    private var activeStartedAt: Long = 0L
    private var lastAnalysisAt: Long = 0L
    private var lastResearchAt: Long = 0L
    private val recentResearchKeys = ArrayDeque<String>()

    init {
        _uiState.update {
            it.copy(
                asrConfigured = apiConfig.hasAsr,
                asrPortable = apiConfig.isPortableAsr,
                asrModeLabel = apiConfig.asrModeLabel,
                llmConfigured = apiConfig.hasLlm,
                llmModeLabel = apiConfig.llmModeLabel,
            )
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startSession() {
        if (_uiState.value.status == SessionStatus.Listening || _uiState.value.status == SessionStatus.Analyzing) return
        scope.launch {
            val session = repository.createSession(scenario = WorkProfile.scenarioName)
            activeStartedAt = session.startedAt
            lastAnalysisAt = 0L
            lastResearchAt = 0L
            recentResearchKeys.clear()
            speakerAttributor.reset()
            _uiState.update {
                it.copy(
                    activeSessionId = session.id,
                    status = SessionStatus.Listening,
                    elapsedMillis = 0L,
                    liveCaption = "",
                    currentSummary = "正在监听周边声音",
                    latestError = null,
                    isAnalyzing = false,
                    transcriptionStatus = "正在启动麦克风",
                    latestInsights = emptyList(),
                )
            }
            startTicker()
            startSystemCaptionIfNeeded(session.id)
            startAppRecorderIfConfigured(session.id)
        }
    }

    fun pauseSession() {
        val sessionId = _uiState.value.activeSessionId ?: return
        captionProvider.stop()
        recorder.stop()
        captionJob?.cancel()
        scope.launch {
            repository.updateStatus(sessionId, SessionStatus.Paused)
            _uiState.update { it.copy(status = SessionStatus.Paused, liveCaption = "已暂停采集") }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun resumeSession() {
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch {
            repository.updateStatus(sessionId, SessionStatus.Listening)
            _uiState.update { it.copy(status = SessionStatus.Listening, latestError = null) }
            startSystemCaptionIfNeeded(sessionId)
            startAppRecorderIfConfigured(sessionId)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startSystemCaptionIfNeeded(sessionId: String) {
        if (apiConfig.usesOnDeviceAsr) {
            _uiState.update {
                it.copy(
                    liveCaption = "${apiConfig.asrModeLabel}已启用，手机会持续采音并在本地转写。",
                    transcriptionStatus = "本机离线识别准备中",
                )
            }
            return
        }
        if (apiConfig.usesSystemSpeech) {
            startCaptionCollection(sessionId)
            _uiState.update {
                it.copy(
                    liveCaption = "系统识别仅作为辅助字幕，翎绛会优先保留自己的采音链路。",
                    transcriptionStatus = "系统辅助识别中",
                )
            }
            captionProvider.start()
            return
        }
        if (apiConfig.hasAsr) {
            _uiState.update {
                it.copy(
                    liveCaption = if (apiConfig.isPortableAsr) {
                        "手机直连转写已启用，字幕会按音频分片实时更新。"
                    } else {
                        "PC 开发桥转写已启用，仅适合调试；正式开会请切换到远端 ASR。"
                    },
                    transcriptionStatus = "采音中",
                )
            }
            return
        }
        startCaptionCollection(sessionId)
        captionProvider.start()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAppRecorderIfConfigured(sessionId: String) {
        if (!apiConfig.hasAsr || apiConfig.usesSystemSpeech) return
        recorder.start(
            scope = scope,
            sessionId = sessionId,
            onChunk = { chunk ->
                if (!chunk.isFinal && transcriptionMutex.isLocked) {
                    chunk.file.delete()
                    _uiState.update { it.copy(transcriptionStatus = "实时转写：跳过过期预览") }
                    return@start
                }
                transcriptionMutex.withLock {
                    val started = System.currentTimeMillis()
                    _uiState.update {
                        it.copy(
                            transcriptionStatus = "%s #%d：%.0f RMS / peak %d".format(
                                if (chunk.isFinal) "提交转写" else "实时转写",
                                chunk.sequence,
                                chunk.rms,
                                chunk.peak,
                            ),
                        )
                    }
                    transcriptionProvider.transcribe(chunk)
                        .onSuccess { result ->
                            Log.i(
                                TAG,
                                "asr sequence=${chunk.sequence} final=${chunk.isFinal} latencyMs=${System.currentTimeMillis() - started} textLength=${result.text.length}",
                            )
                            val text = result.text.trim()
                            if (text.isNotBlank()) {
                                _uiState.update {
                                    it.copy(
                                        liveCaption = text,
                                        transcriptionStatus = "%s #%d：%.1fs".format(
                                            if (chunk.isFinal) "已提交" else "实时字幕",
                                            chunk.sequence,
                                            (chunk.endedAtMillis - chunk.startedAtMillis) / 1000.0,
                                        ),
                                        latestError = null,
                                    )
                                }
                                if (chunk.isFinal) {
                                    val segmentStartMillis = result.startedAtMillis ?: chunk.startedAtMillis
                                    val segmentEndMillis = result.endedAtMillis ?: chunk.endedAtMillis
                                    val speaker = speakerAttributor.assign(chunk, text)
                                    val intent = IntentAnalyzer.analyze(withSpeaker(text, speaker.label))
                                    repository.insertTranscript(
                                        TranscriptSegmentEntity(
                                            id = UUID.randomUUID().toString(),
                                            sessionId = sessionId,
                                            startMillis = (segmentStartMillis - activeStartedAt).coerceAtLeast(0),
                                            endMillis = (segmentEndMillis - activeStartedAt).coerceAtLeast(0),
                                            text = text,
                                            source = if (apiConfig.usesOnDeviceAsr) {
                                                TranscriptSource.OnDeviceAsr.name
                                            } else {
                                                TranscriptSource.CloudAsr.name
                                            },
                                            confidence = result.confidence,
                                            isFinal = true,
                                            createdAt = System.currentTimeMillis(),
                                            speakerLabel = speaker.label,
                                            speakerConfidence = speaker.confidence,
                                            turnIndex = speaker.turnIndex,
                                            intentLabel = intent.label,
                                            intentConfidence = intent.confidence,
                                            intentEvidence = intent.evidence,
                                        ),
                                    )
                                    val speakerText = withSpeaker(text, speaker.label)
                                    maybeSaveFastAnalysis(sessionId, speakerText)
                                    scheduleAnalysis(sessionId, speakerText, force = false)
                                    scheduleResearch(sessionId, speakerText, force = false)
                                } else if (WorkProfile.isHighValue(text)) {
                                    maybeSaveFastAnalysis(sessionId, withSpeaker(text, "实时预览"))
                                }
                            } else {
                            val quietStatus = when {
                                chunk.isImpulsiveNoise -> "采音中：已忽略敲击或冲击噪声"
                                !chunk.isSpeechLikely -> "采音中：已忽略静音或弱语音片段"
                                else -> "采音中：未检测到可转写语音"
                            }
                            _uiState.update {
                                it.copy(transcriptionStatus = quietStatus)
                            }
                        }
                        }
                        .onFailure { throwable ->
                            Log.w(
                                TAG,
                                "asr failed sequence=${chunk.sequence} final=${chunk.isFinal} latencyMs=${System.currentTimeMillis() - started}",
                                throwable,
                            )
                            val message = "转写失败：${throwable.message ?: "未知错误"}"
                            _uiState.update { it.copy(transcriptionStatus = message, latestError = message) }
                            recordErrorInsight(sessionId, message)
                        }
                }
                keepDebugChunk(chunk)
                chunk.file.delete()
            },
            onLevel = { level ->
                _uiState.update {
                    it.copy(
                        micRms = level.rms,
                        micPeak = level.peak,
                        micActiveRatio = level.activeRatio,
                        isSpeechDetected = level.isSpeech,
                        transcriptionStatus = if (level.isSpeech) "采音中：检测到声音" else "采音中：环境较安静",
                    )
                }
            },
            onError = { message -> setError(sessionId, message) },
        )
    }

    fun toggleAnalysisMuted() {
        _uiState.update { it.copy(isAnalysisMuted = !it.isAnalysisMuted) }
    }

    fun analyzeCurrentSituation() {
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch {
            val currentText = repository.recentTranscripts(sessionId, 12)
                .joinToString("\n") { it.text }
            scheduleAnalysis(sessionId, currentText, force = true)
        }
    }

    fun endSession() {
        val sessionId = _uiState.value.activeSessionId ?: return
        captionProvider.stop()
        recorder.stop()
        captionJob?.cancel()
        tickerJob?.cancel()
        scope.launch {
            repository.updateStatus(sessionId, SessionStatus.Reviewing)
            _uiState.update { it.copy(status = SessionStatus.Reviewing, liveCaption = "正在生成复盘报告") }
            delay(1_200L)
            transcriptionMutex.withLock {
                // Wait until the last accepted ASR chunk has been persisted before snapshotting transcripts.
            }

            val snapshot = repository.transcripts(sessionId)
            val current = repository.recentTranscripts(sessionId, 1)
            val title = if (current.isNotEmpty()) "会话 ${formatClock(activeStartedAt)}" else "未命名会话"
            val existing = repository.insights(sessionId)
            val researchTasks = repository.researchTasks(sessionId)
            val actions = repository.actions(sessionId)
            val risks = repository.risks(sessionId)
            val decisions = repository.decisions(sessionId)
            val rollingSummary = _uiState.value.currentSummary

            val fallbackMarkdown = fallbackReport(
                title = title,
                transcript = snapshot.joinToString("\n") { transcriptLine(it) },
                summary = rollingSummary,
                insights = existing.joinToString("\n") { "- ${it.text}（${it.factType}）${evidenceSuffix(it.evidence)}" },
                research = researchTasks.joinToString("\n") {
                    "- ${it.status}｜${it.query}${if (it.summary.isBlank()) "" else "｜${it.summary.take(180)}"}"
                },
                actions = actions.joinToString("\n") { "- ${it.text}｜责任人：${it.owner}｜时间：${it.dueAtText}${evidenceSuffix(it.evidence)}" },
                risks = risks.joinToString("\n") { "- ${it.text}｜${it.severity}｜${it.factType}${evidenceSuffix(it.evidence)}" },
                decisions = decisions.joinToString("\n") { "- ${it.text}｜${it.factType}${evidenceSuffix(it.evidence)}" },
            )

            val report = analysisProvider.finalReport(
                FinalReportRequest(
                    title = title,
                    scenario = WorkProfile.scenarioName,
                    transcript = snapshot.joinToString("\n") { transcriptLine(it) },
                    rollingSummary = rollingSummary,
                    insights = existing.joinToString("\n") { "${it.priority}/${it.category}: ${it.text} 证据:${it.evidence}" } +
                        researchTasks.joinToString(prefix = "\n资料卡：\n", separator = "\n") {
                            "${it.status}: ${it.query} / ${it.title} / ${it.summary} / ${it.sources}"
                        },
                    actions = actions.joinToString("\n") { "${it.text} owner:${it.owner} due:${it.dueAtText} evidence:${it.evidence}" },
                    risks = risks.joinToString("\n") { "${it.severity}: ${it.text} evidence:${it.evidence}" },
                    decisions = decisions.joinToString("\n") { "${it.text} evidence:${it.evidence}" },
                ),
            ).getOrElse {
                com.linglin.lingjiang.network.FinalReportDraft(
                    title = title,
                    overview = rollingSummary.ifBlank { "已生成本地复盘，LLM 报告生成失败：${it.message ?: "未知错误"}" },
                    markdown = fallbackMarkdown,
                )
            }

            repository.saveFinalReport(
                FinalReportEntity(
                    sessionId = sessionId,
                    generatedAt = System.currentTimeMillis(),
                    title = report.title,
                    overview = report.overview,
                    contentMarkdown = report.markdown,
                ),
            )
            repository.endSession(sessionId, report.markdown)
            _uiState.update {
                it.copy(
                    activeSessionId = null,
                    status = SessionStatus.Idle,
                    elapsedMillis = 0L,
                    liveCaption = "",
                    currentSummary = report.overview,
                    isAnalyzing = false,
                )
            }
        }
    }

    private fun startCaptionCollection(sessionId: String) {
        captionJob?.cancel()
        captionJob = scope.launch {
            captionProvider.updates.collectLatest { update ->
                handleCaption(sessionId, update)
            }
        }
    }

    private suspend fun handleCaption(sessionId: String, update: CaptionUpdate) {
        if (update.isDiagnostic) {
            _uiState.update { it.copy(liveCaption = update.text, latestError = update.text) }
            return
        }
        _uiState.update { it.copy(liveCaption = update.text) }
        if (update.isFinal && update.text.isNotBlank()) {
            val now = System.currentTimeMillis()
            val intent = IntentAnalyzer.analyze(update.text)
            repository.insertTranscript(
                TranscriptSegmentEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    startMillis = (now - activeStartedAt).coerceAtLeast(0),
                    endMillis = (now - activeStartedAt).coerceAtLeast(0),
                    text = update.text,
                    source = TranscriptSource.SystemCaption.name,
                    confidence = update.confidence,
                    isFinal = true,
                    createdAt = now,
                    speakerLabel = "说话人待确认",
                    speakerConfidence = null,
                    turnIndex = 0,
                    intentLabel = intent.label,
                    intentConfidence = intent.confidence,
                    intentEvidence = intent.evidence,
                ),
            )
            val speakerText = withSpeaker(update.text, "说话人待确认")
            maybeSaveFastAnalysis(sessionId, speakerText)
            scheduleAnalysis(sessionId, speakerText, force = false)
            scheduleResearch(sessionId, speakerText, force = false)
        }
    }

    private fun scheduleAnalysis(sessionId: String, currentText: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (_uiState.value.isAnalysisMuted && !force) return
        if (!force && apiConfig.usesMnnChatLlm && !apiConfig.hasCloudLlm) return
        if (!EventDetector.shouldAnalyze(currentText, now - lastAnalysisAt, force)) return
        lastAnalysisAt = now
        scope.launch(Dispatchers.IO) {
            if (!analysisMutex.tryLock()) return@launch
            try {
                maybeAnalyze(sessionId, currentText, force)
            } finally {
                analysisMutex.unlock()
            }
        }
    }

    private fun scheduleResearch(sessionId: String, currentText: String, force: Boolean) {
        if (!apiConfig.hasCloudLlm) return
        val now = System.currentTimeMillis()
        if (!force && !shouldResearch(currentText, now - lastResearchAt)) return
        val query = WorkProfile.researchQuery(currentText)
        if (query.isBlank()) return
        val researchKey = normalizeKey(query)
        if (hasRecentResearchKey(researchKey)) return
        lastResearchAt = now
        scope.launch(Dispatchers.IO) {
            if (!researchMutex.tryLock()) return@launch
            if (rememberResearchKey(researchKey).not()) {
                researchMutex.unlock()
                return@launch
            }
            try {
                val taskId = UUID.randomUUID().toString()
                val createdAt = System.currentTimeMillis()
                val task = ResearchTaskEntity(
                    id = taskId,
                    sessionId = sessionId,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    query = query,
                    status = "检索中",
                    title = "",
                    summary = "",
                    sources = "",
                    errorMessage = null,
                )
                repository.saveResearchTask(task)
                val recent = repository.recentTranscripts(sessionId, 16)
                val context = buildAnalysisContext(sessionId, recent)
                val request = ResearchRequest(
                    scenario = WorkProfile.scenarioName,
                    currentText = query,
                    shortWindow = context.shortWindow,
                    structuredState = context.structuredState,
                )
                analysisProvider.research(request)
                    .onSuccess { result ->
                        val sources = result.sources.take(5).joinToString("\n") { "- ${it.title}: ${it.url}" }
                        repository.saveResearchTask(
                            task.copy(
                                updatedAt = System.currentTimeMillis(),
                                status = "已完成",
                                title = result.title,
                                summary = result.summary.take(1200),
                                sources = sources,
                                errorMessage = null,
                            ),
                        )
                        saveResearchInsight(sessionId, result)
                    }
                    .onFailure {
                        Log.w(TAG, "research failed: ${it.message}")
                        repository.saveResearchTask(
                            task.copy(
                                updatedAt = System.currentTimeMillis(),
                                status = "失败",
                                errorMessage = it.message ?: "未知错误",
                            ),
                        )
                    }
            } finally {
                researchMutex.unlock()
            }
        }
    }

    private fun shouldResearch(text: String, millisSinceLastResearch: Long): Boolean {
        if (millisSinceLastResearch < 30_000L) return false
        return WorkProfile.shouldTriggerResearch(text)
    }

    private suspend fun saveResearchInsight(
        sessionId: String,
        result: com.linglin.lingjiang.network.ResearchResult,
    ) {
        val sources = result.sources.take(3).joinToString("\n") { "- ${it.title}: ${it.url}" }
        val queries = result.queries.take(3).joinToString("、")
        val text = buildString {
            append(result.summary.take(600))
            if (queries.isNotBlank()) append("\n建议查询：").append(queries)
            if (sources.isNotBlank()) append("\n来源：\n").append(sources)
        }
        repository.saveAnalysis(
            sessionId = sessionId,
            rollingSummary = _uiState.value.currentSummary,
            oneLineSummary = _uiState.value.currentSummary,
            insights = listOf(
                RealtimeInsightEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    createdAt = System.currentTimeMillis(),
                    priority = InsightPriority.Low.name,
                    category = InsightCategory.Research.name,
                    text = "资料研究：$text",
                    evidence = result.title,
                    spoken = false,
                    factType = "资料",
                ),
            ),
            actions = emptyList(),
            risks = emptyList(),
            decisions = emptyList(),
        )
    }

    private suspend fun maybeSaveFastAnalysis(sessionId: String, currentText: String) {
        val result = FastInsightEngine.analyze(
            currentText = currentText,
            rollingSummary = _uiState.value.currentSummary,
        ) ?: return
        saveAnalysisResult(sessionId, result)
        if (result.researchQueries.isNotEmpty()) {
            scheduleResearch(sessionId, result.researchQueries.joinToString("；"), force = false)
        }
        val status = if (result.insights.any { it.priority.equals("High", true) }) {
            SessionStatus.Alerting
        } else {
            _uiState.value.status
        }
        repository.updateStatus(sessionId, status)
        _uiState.update {
            it.copy(
                status = status,
                currentSummary = result.oneLineSummary.ifBlank { it.currentSummary },
                latestError = null,
            )
        }
    }

    private suspend fun maybeAnalyze(sessionId: String, currentText: String, force: Boolean) {
        _uiState.update { it.copy(status = SessionStatus.Analyzing, isAnalyzing = true) }
        repository.updateStatus(sessionId, SessionStatus.Analyzing)

        val started = System.currentTimeMillis()
        val recent = repository.recentTranscripts(sessionId, 24)
        val context = buildAnalysisContext(sessionId, recent)
        analysisProvider.analyze(
            AnalysisRequest(
                scenario = WorkProfile.scenarioName,
                currentText = currentText,
                shortWindow = context.shortWindow,
                rollingSummary = _uiState.value.currentSummary,
                structuredState = context.structuredState,
                force = force,
            ),
        ).onSuccess { result ->
            Log.i(TAG, "analysis latencyMs=${System.currentTimeMillis() - started} insights=${result.insights.size}")
            saveAnalysisResult(sessionId, result)
            if (result.researchQueries.isNotEmpty()) {
                scheduleResearch(sessionId, result.researchQueries.joinToString("；"), force = true)
            }
            val status = if (result.insights.any { it.priority.equals("High", true) }) {
                SessionStatus.Alerting
            } else {
                SessionStatus.Listening
            }
            repository.updateStatus(sessionId, status)
            _uiState.update {
                it.copy(
                    status = status,
                    currentSummary = result.oneLineSummary.ifBlank { result.rollingSummary },
                    isAnalyzing = false,
                    latestError = null,
                )
            }
        }.onFailure { throwable ->
            Log.w(TAG, "analysis failed latencyMs=${System.currentTimeMillis() - started}", throwable)
            recordErrorInsight(sessionId, "实时分析失败：${throwable.message ?: "未知错误"}")
            repository.updateStatus(sessionId, SessionStatus.Listening)
            _uiState.update {
                it.copy(
                    status = SessionStatus.Listening,
                    isAnalyzing = false,
                    latestError = throwable.message,
                )
            }
        }
    }

    private suspend fun saveAnalysisResult(sessionId: String, result: AnalysisResult) {
        val now = System.currentTimeMillis()
        val existingInsights = repository.insights(sessionId).takeLast(80)
        val existingActions = repository.actions(sessionId).takeLast(80)
        val existingRisks = repository.risks(sessionId).takeLast(80)
        val existingDecisions = repository.decisions(sessionId).takeLast(80)
        val insightKeys = existingInsights.map { normalizeKey("${it.category}:${it.text}:${it.evidence}") }.toMutableSet()
        val actionKeys = existingActions.map { normalizeKey("${it.text}:${it.owner}:${it.dueAtText}") }.toMutableSet()
        val riskKeys = existingRisks.map { normalizeKey("${it.text}:${it.evidence}") }.toMutableSet()
        val decisionKeys = existingDecisions.map { normalizeKey("${it.text}:${it.evidence}") }.toMutableSet()
        val insights = result.insights
            .filter { insightKeys.add(normalizeKey("${it.category}:${it.text}:${it.evidence}")) }
            .map {
                RealtimeInsightEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    createdAt = now,
                    priority = normalizePriority(it.priority),
                    category = normalizeCategory(it.category),
                    text = it.text,
                    evidence = it.evidence,
                    spoken = false,
                    factType = it.factType,
                )
            }
        val actions = result.actionItems
            .filter { actionKeys.add(normalizeKey("${it.text}:${it.owner}:${it.dueAtText}")) }
            .map {
                ActionItemEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    createdAt = now,
                    text = it.text,
                    owner = it.owner,
                    dueAtText = it.dueAtText,
                    evidence = it.evidence,
                    completed = false,
                )
            }
        val risks = result.risks
            .filter { riskKeys.add(normalizeKey("${it.text}:${it.evidence}")) }
            .map {
                RiskEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    createdAt = now,
                    text = it.text,
                    severity = normalizePriority(it.severity),
                    evidence = it.evidence,
                    factType = it.factType,
                )
            }
        val decisions = result.decisions
            .filter { decisionKeys.add(normalizeKey("${it.text}:${it.evidence}")) }
            .map {
                DecisionEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    createdAt = now,
                    text = it.text,
                    evidence = it.evidence,
                    factType = it.factType,
                )
            }
        repository.saveAnalysis(
            sessionId = sessionId,
            rollingSummary = result.rollingSummary,
            oneLineSummary = result.oneLineSummary,
            insights = insights,
            actions = actions,
            risks = risks,
            decisions = decisions,
        )
        val liveCards = buildLiveInsightCards(insights, actions, risks, decisions)
        if (liveCards.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    latestInsights = (liveCards + it.latestInsights)
                        .distinctBy { card -> normalizeKey("${card.category}:${card.text}:${card.evidence}") }
                        .take(6),
                )
            }
        }
    }

    private fun buildLiveInsightCards(
        insights: List<RealtimeInsightEntity>,
        actions: List<ActionItemEntity>,
        risks: List<RiskEntity>,
        decisions: List<DecisionEntity>,
    ): List<LiveInsight> {
        val insightCards = insights
            .filter { it.priority != InsightPriority.Low.name || it.category == InsightCategory.Research.name }
            .map {
                LiveInsight(
                    priority = it.priority,
                    category = it.category,
                    text = it.text,
                    evidence = it.evidence,
                    factType = it.factType,
                )
            }
        val actionCards = actions.map {
            LiveInsight(
                priority = InsightPriority.High.name,
                category = InsightCategory.Action.name,
                text = "待办：${it.text}",
                evidence = it.evidence,
                factType = "事实",
            )
        }
        val riskCards = risks.map {
            LiveInsight(
                priority = it.severity,
                category = InsightCategory.Risk.name,
                text = "风险：${it.text}",
                evidence = it.evidence,
                factType = it.factType,
            )
        }
        val decisionCards = decisions.map {
            LiveInsight(
                priority = InsightPriority.Medium.name,
                category = InsightCategory.Decision.name,
                text = "结论：${it.text}",
                evidence = it.evidence,
                factType = it.factType,
            )
        }
        return (insightCards + actionCards + riskCards + decisionCards).take(5)
    }

    private suspend fun buildAnalysisContext(
        sessionId: String,
        recent: List<TranscriptSegmentEntity>,
    ): AnalysisContextSnapshot =
        ContextManager.build(
            recent = recent,
            actions = repository.actions(sessionId),
            risks = repository.risks(sessionId),
            decisions = repository.decisions(sessionId),
            rollingSummary = _uiState.value.currentSummary,
        )

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(1_000L)
                if (activeStartedAt > 0) {
                    _uiState.update { it.copy(elapsedMillis = System.currentTimeMillis() - activeStartedAt) }
                }
            }
        }
    }

    private suspend fun setError(sessionId: String, message: String) {
        repository.updateStatus(sessionId, SessionStatus.Error, message)
        recordErrorInsight(sessionId, message)
        _uiState.update { it.copy(status = SessionStatus.Error, latestError = message) }
    }

    private suspend fun recordErrorInsight(sessionId: String, message: String) {
        repository.saveAnalysis(
            sessionId = sessionId,
            rollingSummary = _uiState.value.currentSummary,
            oneLineSummary = _uiState.value.currentSummary,
            insights = listOf(
                RealtimeInsightEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    createdAt = System.currentTimeMillis(),
                    priority = InsightPriority.Medium.name,
                    category = InsightCategory.Error.name,
                    text = message,
                    evidence = "",
                    spoken = false,
                    factType = "系统状态",
                ),
            ),
            actions = emptyList(),
            risks = emptyList(),
            decisions = emptyList(),
        )
    }

    private fun fallbackReport(
        title: String,
        transcript: String,
        summary: String,
        insights: String,
        research: String,
        actions: String,
        risks: String,
        decisions: String,
    ): String = """
        # $title

        ## 一句话总览
        ${summary.ifBlank { "本次会话已结束。由于模型报告生成不可用，以下为本地汇总。" }}

        ## 关键结论
        ${decisions.ifBlank { "- 暂无明确结论。" }}

        ## 待办事项
        ${actions.ifBlank { "- 暂无明确待办。" }}

        ## 风险与分歧
        ${risks.ifBlank { "- 暂无明确风险。" }}

        ## 实时建议回顾
        ${insights.ifBlank { "- 暂无实时建议。" }}

        ## 后台资料卡
        ${research.ifBlank { "- 暂无后台资料卡。" }}

        ## 完整转写记录
        ${transcript.ifBlank { "暂无转写内容。" }}
    """.trimIndent()

    private fun normalizePriority(value: String): String =
        InsightPriority.entries.firstOrNull { it.name.equals(value, true) }?.name ?: InsightPriority.Medium.name

    private fun normalizeCategory(value: String): String =
        InsightCategory.entries.firstOrNull { it.name.equals(value, true) }?.name ?: InsightCategory.Suggestion.name

    private fun normalizeKey(value: String): String =
        value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\s+"), "")
            .replace(Regex("[，。！？、,.!?:：；;\\-_/|]+"), "")
            .take(120)

    private fun rememberResearchKey(key: String): Boolean {
        if (key.isBlank()) return false
        if (recentResearchKeys.contains(key)) return false
        recentResearchKeys.addLast(key)
        while (recentResearchKeys.size > MAX_RECENT_RESEARCH_KEYS) {
            recentResearchKeys.removeFirst()
        }
        return true
    }

    private fun hasRecentResearchKey(key: String): Boolean =
        key.isNotBlank() && recentResearchKeys.contains(key)

    private fun formatOffset(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun formatClock(millis: Long): String = java.text.SimpleDateFormat(
        "MM-dd HH:mm",
        java.util.Locale.CHINA,
    ).format(java.util.Date(millis))

    private fun evidenceSuffix(evidence: String): String =
        if (evidence.isBlank()) "" else "｜证据：$evidence"

    private fun withSpeaker(text: String, speakerLabel: String): String =
        if (speakerLabel.isBlank()) text else "$speakerLabel：$text"

    private fun transcriptLine(segment: TranscriptSegmentEntity): String {
        val speaker = segment.speakerLabel.ifBlank { "说话人待确认" }
        val intent = segment.intentLabel.takeIf { it.isNotBlank() && it != "未判断" }?.let { "｜意图:$it" }.orEmpty()
        return "[${formatOffset(segment.startMillis)}] $speaker$intent：${segment.text}"
    }

    private fun formatConfidence(value: Float?): String =
        value?.let { "%.0f%%".format(it * 100) } ?: "未知"

    private fun keepDebugChunk(chunk: AudioChunk) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = java.io.File(appContext.cacheDir, "debug_audio/${chunk.sessionId}").apply { mkdirs() }
            val name = "%05d_%s_%dms.wav".format(
                chunk.sequence,
                if (chunk.isFinal) "final" else "partial",
                chunk.endedAtMillis - chunk.startedAtMillis,
            )
            chunk.file.copyTo(java.io.File(dir, name), overwrite = true)
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(DEBUG_AUDIO_KEEP_COUNT)
                ?.forEach { it.delete() }
        }.onFailure {
            Log.w(TAG, "failed to keep debug audio chunk", it)
        }
    }

    private companion object {
        private const val TAG = "LingJiangSession"
        private const val DEBUG_AUDIO_KEEP_COUNT = 40
        private const val MAX_RECENT_RESEARCH_KEYS = 12
    }
}
