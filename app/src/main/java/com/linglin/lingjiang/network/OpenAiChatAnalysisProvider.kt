package com.linglin.lingjiang.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiChatAnalysisProvider(
    private val client: OkHttpClient,
    private val config: ApiConfig,
) : LlmAnalysisProvider {
    override suspend fun analyze(request: AnalysisRequest): Result<AnalysisResult> = withContext(Dispatchers.IO) {
        val target = analysisTarget()
        if (target == null) {
            return@withContext Result.failure(IllegalStateException("LLM 接口未配置"))
        }
        runCatching {
            val content = chat(
                target = target,
                model = realtimeModel(target.model),
                system = ANALYSIS_SYSTEM,
                user = buildAnalysisPrompt(request),
                    timeoutSeconds = if (target.isCloud) 18 else 12,
            )
            parseAnalysis(content, request.rollingSummary)
        }
    }

    override suspend fun research(request: ResearchRequest): Result<ResearchResult> = withContext(Dispatchers.IO) {
        val target = cloudTarget() ?: return@withContext Result.failure(IllegalStateException("云端资料研究接口未配置"))
        runCatching {
            if (target.isOpenAi) {
                researchWithWebSearch(target, request)
            } else {
                researchWithChat(target, request)
            }
        }
    }

    override suspend fun finalReport(request: FinalReportRequest): Result<FinalReportDraft> =
        withContext(Dispatchers.IO) {
            val target = analysisTarget()
            if (target == null) {
                return@withContext Result.failure(IllegalStateException("LLM 接口未配置"))
            }
            runCatching {
                val content = chat(
                    target = target,
                    model = target.model,
                    system = REPORT_SYSTEM,
                    user = buildReportPrompt(request),
                    timeoutSeconds = 90,
                )
                parseReport(content, request.title)
            }
        }

    private data class LlmTarget(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val isCloud: Boolean,
    ) {
        val isOpenAi: Boolean
            get() = baseUrl.contains("api.openai.com", ignoreCase = true)
    }

    private fun analysisTarget(): LlmTarget? =
        cloudTarget() ?: localTarget()

    private fun cloudTarget(): LlmTarget? =
        if (config.hasCloudLlm) {
            LlmTarget(config.cloudLlmBaseUrl, config.cloudLlmApiKey, config.cloudLlmModel, isCloud = true)
        } else {
            null
        }

    private fun localTarget(): LlmTarget? =
        if (config.hasLlm) {
            LlmTarget(config.llmBaseUrl, config.llmApiKey, config.llmModel, isCloud = false)
        } else {
            null
        }

    private fun chat(target: LlmTarget, model: String, system: String, user: String, timeoutSeconds: Long): String {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val request = Request.Builder()
            .url(endpoint(target.baseUrl, "chat/completions"))
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                if (target.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${target.apiKey}")
                }
            }
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("LLM 请求失败 ${response.code}: $raw")
            val json = JSONObject(raw)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    private fun realtimeModel(model: String): String =
        when (model) {
            "gpt-5.5", "gpt-5.4", "gpt-5.2-pro" -> "gpt-5.4-mini"
            else -> model
        }

    private fun buildAnalysisPrompt(request: AnalysisRequest): String = """
        场景：${request.scenario}
        是否用户主动分析：${request.force}

        当前新内容：
        ${request.currentText}

        最近短期窗口：
        ${request.shortWindow}

        长期滚动摘要：
        ${request.rollingSummary.ifBlank { "暂无" }}

        已有结构化状态（包含待办、风险、结论、最近意图、说话人状态）：
        ${request.structuredState.ifBlank { "暂无" }}

        请快速判断，不要展开长篇解释。只返回 JSON，不要 Markdown。字段：
        {
          "one_line_summary": "一句话当前局势",
          "rolling_summary": "更新后的长期滚动摘要",
          "insights": [{"priority":"Low|Medium|High","category":"Suggestion|Risk|Question|Action|Decision|Summary|Intent|Research","text":"短建议","evidence":"原文依据","fact_type":"事实|推断|建议|资料"}],
          "action_items": [{"text":"待办","owner":"责任人或未知","due_at_text":"时间或未定","evidence":"原文依据"}],
          "risks": [{"text":"风险","severity":"Low|Medium|High","evidence":"原文依据","fact_type":"事实|推断"}],
          "decisions": [{"text":"结论","evidence":"原文依据","fact_type":"事实|推断"}],
          "research_queries": ["需要后台搜索核验的关键词或问题，最多3条"]
        }
        必须先判断当前发言意图，再从原文提取关键事实，不要泛泛总结。若出现产品、公司、法律、价格、技术术语、竞品、人物、地点、政策、合同条款，给出 research_queries。
        不能把意图推断当事实；意图类 insight 的 fact_type 必须为“推断”。
        实时建议要少而准，没有高价值内容时 insights 可以为空数组。每个数组最多 3 条。
    """.trimIndent()

    private fun buildResearchPrompt(request: ResearchRequest): String = """
        你是翎绛的后台资料研究员。请根据会议转写提取值得核验或补充资料的主题，并联网搜索。
        场景：${request.scenario}

        当前内容：
        ${request.currentText}

        最近窗口：
        ${request.shortWindow}

        结构化状态：
        ${request.structuredState}

        只输出与当前对话决策有关的信息：事实背景、价格/政策/技术资料、风险点、可追问问题。
        不要编造来源。优先给出可点击来源。
    """.trimIndent()

    private fun buildReportPrompt(request: FinalReportRequest): String = """
        标题：${request.title}
        场景：${request.scenario}

        完整转写（带说话人和意图标签）：
        ${request.transcript}

        滚动摘要：
        ${request.rollingSummary}

        实时建议：
        ${request.insights}

        待办：
        ${request.actions}

        风险：
        ${request.risks}

        结论：
        ${request.decisions}

        请返回 JSON，不要 Markdown 代码块：
        {
          "title": "会话标题",
          "overview": "一句话总览",
          "markdown": "完整 Markdown 报告，必须包含：主要讨论、关键结论、待办、风险与分歧、重要时间线、实时建议回顾、完整转写摘要、事实/推断区分、关键结论的原文依据"
        }
    """.trimIndent()

    private fun parseAnalysis(content: String, fallbackSummary: String): AnalysisResult {
        val json = JSONObject(extractJson(content))
        return AnalysisResult(
            oneLineSummary = json.optString("one_line_summary"),
            rollingSummary = json.optString("rolling_summary", fallbackSummary),
            insights = json.optJSONArray("insights").toInsightDrafts(),
            actionItems = json.optJSONArray("action_items").toActionDrafts(),
            risks = json.optJSONArray("risks").toRiskDrafts(),
            decisions = json.optJSONArray("decisions").toDecisionDrafts(),
            researchQueries = json.optJSONArray("research_queries").toStringList(),
        )
    }

    private fun researchWithWebSearch(target: LlmTarget, request: ResearchRequest): ResearchResult {
        val payload = JSONObject()
            .put("model", target.model)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search_preview")))
            .put("input", buildResearchPrompt(request))
        val httpRequest = Request.Builder()
            .url(endpoint(target.baseUrl, "responses"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${target.apiKey}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(httpRequest).also { it.timeout().timeout(30, TimeUnit.SECONDS) }.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("资料查询失败 ${response.code}: $raw")
            val json = JSONObject(raw)
            val text = extractResponseText(json).ifBlank { raw.take(1200) }
            return ResearchResult(
                title = "后台资料研究",
                summary = text,
                queries = extractSearchQueries(request.currentText),
                sources = extractSources(json),
            )
        }
    }

    private fun researchWithChat(target: LlmTarget, request: ResearchRequest): ResearchResult {
        val content = chat(
            target = target,
            model = target.model,
            system = RESEARCH_SYSTEM,
            user = buildResearchPrompt(request) + "\n请返回 JSON：{\"title\":\"标题\",\"summary\":\"资料摘要\",\"queries\":[\"建议搜索词\"],\"sources\":[]}",
            timeoutSeconds = 30,
        )
        val json = runCatching { JSONObject(extractJson(content)) }.getOrNull()
        return ResearchResult(
            title = json?.optString("title", "后台资料研究") ?: "后台资料研究",
            summary = json?.optString("summary", content) ?: content,
            queries = json?.optJSONArray("queries").toStringList(),
            sources = emptyList(),
        )
    }

    private fun parseReport(content: String, fallbackTitle: String): FinalReportDraft {
        val json = JSONObject(extractJson(content))
        return FinalReportDraft(
            title = json.optString("title", fallbackTitle),
            overview = json.optString("overview"),
            markdown = json.optString("markdown", content),
        )
    }

    private fun JSONArray?.toInsightDrafts(): List<InsightDraft> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let {
                InsightDraft(
                    priority = it.optString("priority", "Medium"),
                    category = it.optString("category", "Suggestion"),
                    text = it.optString("text"),
                    evidence = it.optString("evidence"),
                    factType = it.optString("fact_type", "建议"),
                )
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun JSONArray?.toActionDrafts(): List<ActionDraft> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let {
                ActionDraft(
                    text = it.optString("text"),
                    owner = it.optString("owner", "未知"),
                    dueAtText = it.optString("due_at_text", "未定"),
                    evidence = it.optString("evidence"),
                )
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun JSONArray?.toRiskDrafts(): List<RiskDraft> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let {
                RiskDraft(
                    text = it.optString("text"),
                    severity = it.optString("severity", "Medium"),
                    evidence = it.optString("evidence"),
                    factType = it.optString("fact_type", "推断"),
                )
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun JSONArray?.toDecisionDrafts(): List<DecisionDraft> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let {
                DecisionDraft(
                    text = it.optString("text"),
                    evidence = it.optString("evidence"),
                    factType = it.optString("fact_type", "事实"),
                )
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun extractSearchQueries(text: String): List<String> =
        text.split('，', '。', ',', '.', '\n')
            .map { it.trim() }
            .filter { it.length >= 4 }
            .take(3)

    private fun extractResponseText(json: JSONObject): String {
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = json.optJSONArray("output") ?: return ""
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                val text = item.optString("text")
                if (text.isNotBlank()) parts += text
            }
        }
        return parts.joinToString("\n").trim()
    }

    private fun extractSources(json: JSONObject): List<ResearchSource> {
        val sources = mutableListOf<ResearchSource>()
        val output = json.optJSONArray("output") ?: return emptyList()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val annotations = content.optJSONObject(j)?.optJSONArray("annotations") ?: continue
                for (k in 0 until annotations.length()) {
                    val annotation = annotations.optJSONObject(k) ?: continue
                    val url = annotation.optString("url")
                    if (url.isNotBlank()) {
                        sources += ResearchSource(
                            title = annotation.optString("title", url),
                            url = url,
                            snippet = annotation.optString("text"),
                        )
                    }
                }
            }
        }
        return sources.distinctBy { it.url }.take(6)
    }

    private fun extractJson(content: String): String {
        val trimmed = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    companion object {
        private const val ANALYSIS_SYSTEM =
            "你是翎绛的旁路听觉分析核心。你不参与对话、不陪聊，只做克制、可靠、带证据的实时分析。"
        private const val REPORT_SYSTEM =
            "你是翎绛的会话复盘生成器。你必须区分事实、推断和建议，并保留原文依据。"
        private const val RESEARCH_SYSTEM =
            "你是翎绛的后台资料研究员。你只做与当前对话有关的事实核验、资料补充和可追问问题。"
    }
}
