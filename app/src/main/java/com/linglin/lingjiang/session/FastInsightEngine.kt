package com.linglin.lingjiang.session

import com.linglin.lingjiang.network.ActionDraft
import com.linglin.lingjiang.network.AnalysisResult
import com.linglin.lingjiang.network.DecisionDraft
import com.linglin.lingjiang.network.InsightDraft
import com.linglin.lingjiang.network.RiskDraft

object FastInsightEngine {
    private val timePattern = Regex("(今天|今晚|明天|明早|后天|本周|下周|月底|月初|周[一二三四五六日天]|\\d{1,2}[点:：]\\d{0,2}|\\d{1,2}月\\d{1,2}日|\\d{1,2}天内)")
    private val moneyPattern = Regex("(\\d+(?:\\.\\d+)?\\s*(?:元|块|万|万元|千|k|K|w|W)|预算|报价|价格|费用|成本|含税|不含税)")
    private val ownerPattern = Regex("(我|你|他|她|我们|你们|他们|这边|那边|供应商|厂家|客户|张\\S{0,2}|李\\S{0,2}|王\\S{0,2}).{0,10}(负责|来做|处理|跟进|确认|整理|发给|提交|推进|对接)")
    private val techPattern = Regex("(接口|API|SDK|模型|架构|部署|延迟|精度|并发|兼容|数据库|方案|验收|指标|MNN|OpenAI|Android|安卓|Paraformer|sherpa)", RegexOption.IGNORE_CASE)
    private val riskWords = listOf("风险", "但是", "不确定", "可能", "尽量", "大概", "差不多", "后面再说", "不保证", "来不及", "延期", "问题", "卡住", "不稳定", "效果差", "不好说")
    private val decisionWords = listOf("确定", "确认", "决定", "就这样", "按这个", "同意", "可以", "通过", "拍板", "定下来")
    private val questionMarks = listOf("吗", "呢", "怎么", "为什么", "什么时候", "多少钱", "能不能", "是否", "?","？")

    fun analyze(currentText: String, rollingSummary: String): AnalysisResult? {
        val compact = currentText.trim()
        if (compact.length < MIN_TEXT_LENGTH) return null

        val insights = mutableListOf<InsightDraft>()
        val actions = mutableListOf<ActionDraft>()
        val risks = mutableListOf<RiskDraft>()
        val decisions = mutableListOf<DecisionDraft>()
        val intent = IntentAnalyzer.analyze(compact)

        if (intent.label != "信息陈述" || intent.confidence >= 0.55f) {
            insights += InsightDraft(
                priority = intent.priority,
                category = "Intent",
                text = "${intent.label}：${intent.suggestedHandling}",
                evidence = intent.evidence,
                factType = "推断",
            )
        }

        val hasTime = timePattern.containsMatchIn(compact)
        val hasMoney = moneyPattern.containsMatchIn(compact)
        val hasOwner = ownerPattern.containsMatchIn(compact)
        val hasTech = techPattern.containsMatchIn(compact)
        val riskWord = riskWords.firstOrNull { compact.contains(it) }
        val decisionWord = decisionWords.firstOrNull { compact.contains(it) }
        val hasQuestion = questionMarks.any { compact.contains(it) }
        val shouldResearch = WorkProfile.shouldTriggerResearch(compact)

        if (hasOwner || hasTime) {
            val text = when {
                hasOwner && hasTime -> "出现责任人与时间信息，建议当场确认交付物和截止口径。"
                hasOwner -> "出现责任分配，建议确认具体交付物。"
                else -> "出现时间节点，建议确认是否是明确截止时间。"
            }
            insights += InsightDraft(
                priority = "High",
                category = "Action",
                text = text,
                evidence = compact.take(EVIDENCE_LIMIT),
                factType = "建议",
            )
            actions += ActionDraft(
                text = "确认责任、交付物和截止时间",
                owner = "未知",
                dueAtText = if (hasTime) "见原文时间" else "未定",
                evidence = compact.take(EVIDENCE_LIMIT),
            )
        }

        if (hasTech) {
            insights += InsightDraft(
                priority = if (WorkProfile.isHighValue(compact)) "High" else "Medium",
                category = "Suggestion",
                text = "涉及技术/方案判断，建议马上锁定指标、约束、替代方案和验证方式。",
                evidence = compact.take(EVIDENCE_LIMIT),
                factType = "建议",
            )
        }

        if (hasMoney) {
            insights += InsightDraft(
                priority = "High",
                category = "Risk",
                text = "出现金额或成本信息，建议确认报价范围、是否含税以及变更条件。",
                evidence = compact.take(EVIDENCE_LIMIT),
                factType = "建议",
            )
        }

        if (riskWord != null) {
            risks += RiskDraft(
                text = "存在模糊或风险信号：$riskWord",
                severity = "Medium",
                evidence = compact.take(EVIDENCE_LIMIT),
                factType = "推断",
            )
            insights += InsightDraft(
                priority = "Medium",
                category = "Risk",
                text = "这句话可能需要澄清边界，避免后续理解不一致。",
                evidence = compact.take(EVIDENCE_LIMIT),
                factType = "推断",
            )
        }

        if (hasQuestion) {
            insights += InsightDraft(
                priority = "Medium",
                category = "Question",
                text = "对方可能在等待回答，建议先确认目标、约束和风险再表态。",
                evidence = compact.take(EVIDENCE_LIMIT),
                factType = "推断",
            )
        }

        if (decisionWord != null) {
            decisions += DecisionDraft(
                text = "可能形成阶段性结论：$decisionWord",
                evidence = compact.take(EVIDENCE_LIMIT),
                factType = "推断",
            )
        }

        if (insights.isEmpty() && actions.isEmpty() && risks.isEmpty() && decisions.isEmpty()) return null

        val summary = buildSummary(intent.label, compact, hasOwner, hasTime, hasMoney, riskWord, hasQuestion)
        val researchQueries = if (shouldResearch) listOf(WorkProfile.researchQuery(compact)) else emptyList()
        return AnalysisResult(
            oneLineSummary = summary,
            rollingSummary = rollingSummary.ifBlank { summary },
            insights = insights.take(3),
            actionItems = actions.take(2),
            risks = risks.take(2),
            decisions = decisions.take(2),
            researchQueries = researchQueries,
        )
    }

    private fun buildSummary(
        intent: String,
        text: String,
        hasOwner: Boolean,
        hasTime: Boolean,
        hasMoney: Boolean,
        riskWord: String?,
        hasQuestion: Boolean,
    ): String {
        val tags = buildList {
            if (hasOwner) add("责任")
            if (hasTime) add("时间")
            if (hasMoney) add("金额")
            if (riskWord != null) add("风险")
            if (hasQuestion) add("问题")
        }.joinToString("、")
        return if (tags.isBlank()) {
            text.take(40)
        } else {
            "意图：$intent；刚捕捉到$tags 信号"
        }
    }

    private const val MIN_TEXT_LENGTH = 4
    private const val EVIDENCE_LIMIT = 80
}
