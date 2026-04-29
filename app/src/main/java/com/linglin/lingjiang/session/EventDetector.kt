package com.linglin.lingjiang.session

object EventDetector {
    private val keywords = listOf(
        "承诺",
        "确认",
        "决定",
        "负责",
        "截止",
        "明天",
        "今天",
        "下周",
        "价格",
        "预算",
        "风险",
        "问题",
        "不确定",
        "拒绝",
        "但是",
        "需要你",
        "怎么办",
        "为什么",
        "多少钱",
        "什么时候",
        "接口",
        "API",
        "SDK",
        "模型",
        "方案",
        "架构",
        "部署",
        "延迟",
        "精度",
        "验收",
        "供应商",
        "合同",
        "报价单",
        "标准",
        "竞品",
    )

    fun shouldAnalyze(text: String, millisSinceLastAnalysis: Long, force: Boolean = false): Boolean {
        if (force) return true
        val compact = text.trim()
        if (compact.length >= 40 && millisSinceLastAnalysis >= 5_000L) return true
        if (millisSinceLastAnalysis >= 10_000L && compact.length >= 12) return true
        return compact.length >= 8 && keywords.any { compact.contains(it, ignoreCase = true) } && millisSinceLastAnalysis >= 3_000L
    }
}
