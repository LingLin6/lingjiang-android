package com.linglin.lingjiang.session

import com.linglin.lingjiang.data.ActionItemEntity
import com.linglin.lingjiang.data.DecisionEntity
import com.linglin.lingjiang.data.RiskEntity
import com.linglin.lingjiang.data.TranscriptSegmentEntity

data class AnalysisContextSnapshot(
    val shortWindow: String,
    val structuredState: String,
)

object ContextManager {
    fun build(
        recent: List<TranscriptSegmentEntity>,
        actions: List<ActionItemEntity>,
        risks: List<RiskEntity>,
        decisions: List<DecisionEntity>,
        rollingSummary: String,
    ): AnalysisContextSnapshot {
        val shortWindow = recent.takeLast(SHORT_WINDOW_LIMIT).joinToString("\n") { transcriptLine(it) }
        val intentTrail = recent.takeLast(INTENT_TRAIL_LIMIT).joinToString("\n") {
            "- ${formatOffset(it.startMillis)} ${speaker(it)}：${it.intentLabel} ${formatConfidence(it.intentConfidence)} / ${it.text.take(60)}"
        }
        val openQuestions = recent
            .asReversed()
            .filter { it.intentLabel in OPEN_LOOP_INTENTS || QUESTION_MARKS.any { mark -> it.text.contains(mark) } }
            .take(6)
            .asReversed()
            .joinToString("\n") { "- ${formatOffset(it.startMillis)} ${speaker(it)}：${it.text.take(90)}" }
        val researchCandidates = recent
            .asReversed()
            .map { WorkProfile.researchQuery(it.text) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .joinToString("\n") { "- $it" }
        val speakerStates = recent.groupBy { speaker(it) }
            .map { (speaker, segments) ->
                val latest = segments.lastOrNull()
                "- $speaker：${segments.size}段，最近意图 ${latest?.intentLabel ?: "未判断"}，最近内容 ${latest?.text?.take(42).orEmpty()}"
            }
            .joinToString("\n")

        val structuredState = """
            工作画像：
            ${WorkProfile.promptBrief}

            长期摘要：
            ${rollingSummary.ifBlank { "暂无" }}

            已记录待办：
            ${actions.takeLast(10).joinToString("\n") { "- ${it.text}｜责任人：${it.owner}｜时间：${it.dueAtText}｜证据：${it.evidence}" }.ifBlank { "暂无" }}

            已记录风险：
            ${risks.takeLast(10).joinToString("\n") { "- ${it.severity}｜${it.text}｜${it.factType}｜证据：${it.evidence}" }.ifBlank { "暂无" }}

            已记录结论：
            ${decisions.takeLast(10).joinToString("\n") { "- ${it.text}｜${it.factType}｜证据：${it.evidence}" }.ifBlank { "暂无" }}

            未闭环问题：
            ${openQuestions.ifBlank { "暂无" }}

            最近意图轨迹：
            ${intentTrail.ifBlank { "暂无" }}

            说话人状态：
            ${speakerStates.ifBlank { "暂无" }}

            可能需要资料核验：
            ${researchCandidates.ifBlank { "暂无" }}
        """.trimIndent()

        return AnalysisContextSnapshot(shortWindow = shortWindow, structuredState = structuredState)
    }

    private fun transcriptLine(segment: TranscriptSegmentEntity): String {
        val intent = segment.intentLabel
            .takeIf { it.isNotBlank() && it != "未判断" }
            ?.let { "｜意图:$it" }
            .orEmpty()
        return "[${formatOffset(segment.startMillis)}] ${speaker(segment)}$intent：${segment.text}"
    }

    private fun speaker(segment: TranscriptSegmentEntity): String =
        segment.speakerLabel.ifBlank { "说话人待确认" }

    private fun formatOffset(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun formatConfidence(value: Float?): String =
        value?.let { "(%.0f%%)".format(it * 100) } ?: "(未知)"

    private val OPEN_LOOP_INTENTS = setOf("请求决策", "提问澄清", "资料查询", "资料核验", "风险暴露", "范围变更")
    private val QUESTION_MARKS = listOf("吗", "呢", "？", "?")
    private const val SHORT_WINDOW_LIMIT = 30
    private const val INTENT_TRAIL_LIMIT = 14
}
