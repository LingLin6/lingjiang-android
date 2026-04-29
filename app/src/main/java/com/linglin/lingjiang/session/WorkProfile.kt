package com.linglin.lingjiang.session

object WorkProfile {
    const val scenarioName: String = "LingLin 专项工作副驾"

    val researchKeywords = listOf(
        "API",
        "SDK",
        "接口",
        "模型",
        "部署",
        "延迟",
        "精度",
        "成本",
        "报价",
        "供应商",
        "竞品",
        "政策",
        "法规",
        "标准",
        "合同",
        "验收",
        "方案",
        "架构",
        "数据库",
        "Android",
        "安卓",
        "OpenAI",
        "MNN",
        "sherpa",
        "Paraformer",
    )

    val highValueKeywords = listOf(
        "谁负责",
        "你负责",
        "我负责",
        "截止",
        "验收",
        "报价",
        "成本",
        "风险",
        "延期",
        "不确定",
        "卡住",
        "来不及",
        "要不要",
        "怎么定",
        "能不能",
        "接口",
        "模型",
        "方案",
        "供应商",
    )

    val promptBrief: String = """
        用户画像：LingLin 更需要会议/沟通中的项目推进副驾，而不是聊天陪伴。
        优先关注：责任人、截止时间、验收口径、价格/成本、技术方案取舍、供应商/外部协作、风险/分歧、需要联网核验的资料。
        输出策略：实时建议必须短、具体、可当场追问；低价值闲聊只记录不提醒；不要把每句话都总结一遍。
        决策风格：先锁定事实和证据，再给下一句追问；把事实、推断、建议分开。
        研究策略：只在出现技术名词、公司/产品/政策/价格/合同/标准/竞品时触发后台资料卡。
    """.trimIndent()

    fun isHighValue(text: String): Boolean =
        highValueKeywords.any { text.contains(it, ignoreCase = true) }

    fun shouldTriggerResearch(text: String): Boolean {
        if (text.length < 8) return false
        return researchKeywords.any { text.contains(it, ignoreCase = true) } ||
            Regex("[A-Za-z][A-Za-z0-9._/-]{2,}").containsMatchIn(text)
    }

    fun researchQuery(text: String): String {
        val compact = text
            .replace(Regex("说话人[^：:]{0,8}[：:]"), "")
            .replace('\n', ' ')
            .trim()
        val keywordHits = researchKeywords
            .filter { compact.contains(it, ignoreCase = true) }
            .take(5)
            .joinToString(" ")
        return listOf(keywordHits, compact.take(120))
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }
}
