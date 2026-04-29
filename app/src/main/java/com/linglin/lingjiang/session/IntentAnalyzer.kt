package com.linglin.lingjiang.session

data class IntentAnalysis(
    val label: String,
    val confidence: Float,
    val evidence: String,
    val priority: String,
    val suggestedHandling: String,
)

object IntentAnalyzer {
    private val patterns = listOf(
        IntentRule("请求决策", "High", "对方可能在等你表态，先确认目标、约束和代价再决定。", listOf("你看", "你觉得", "要不要", "能不能", "是否", "行不行", "可以吗", "怎么定", "怎么选", "拍板")),
        IntentRule("明确承诺", "High", "这可能形成承诺，建议确认责任人、交付物和截止时间。", listOf("我负责", "我们负责", "我来", "我们来", "保证", "一定", "会在", "提交", "交付", "今天给", "明天给")),
        IntentRule("任务分配", "High", "这里出现任务分配，建议确认谁做、做什么、何时完成。", listOf("你负责", "你来", "安排", "跟进", "处理", "整理", "发给", "确认一下", "推进")),
        IntentRule("价格谈判", "High", "这里涉及价格或成本，建议确认口径、范围、税费和变更条件。", listOf("价格", "报价", "预算", "费用", "成本", "便宜", "贵", "多少钱", "元", "万", "含税", "不含税")),
        IntentRule("风险暴露", "High", "对方在暴露风险或不确定性，建议追问影响、概率和补救方案。", listOf("风险", "问题", "来不及", "延期", "不确定", "不保证", "可能不行", "卡住", "不稳定", "效果差")),
        IntentRule("技术方案判断", "High", "这里可能涉及方案取舍，建议锁定指标、约束、替代方案和验证方法。", listOf("接口", "API", "SDK", "模型", "架构", "部署", "延迟", "精度", "并发", "兼容", "数据库", "方案", "实现")),
        IntentRule("供应商协作", "High", "这里涉及外部协作，建议确认交付边界、响应时限和验收标准。", listOf("供应商", "厂家", "客户", "外包", "对接", "商务", "合同", "报价单", "交付周期")),
        IntentRule("验收边界", "High", "这里在定义边界或验收，建议把口径写成可验证条件。", listOf("验收", "边界", "口径", "标准", "范围", "前提", "依赖", "指标")),
        IntentRule("范围变更", "Medium", "可能发生需求或范围变化，建议确认是否影响价格、周期和验收。", listOf("顺便", "再加", "改一下", "调整", "扩展", "新增", "改需求")),
        IntentRule("拒绝或回避", "Medium", "对方可能在拒绝或回避，建议换成具体条件追问。", listOf("不行", "做不了", "没办法", "不好说", "后面再说", "看情况", "尽量")),
        IntentRule("确认理解", "Medium", "这可能是在确认共识，建议复述关键点。", listOf("确认", "是不是", "对吧", "没问题", "就这样", "按这个")),
        IntentRule("资料核验", "Medium", "这段需要外部资料支撑，适合后台搜索后回填资料卡。", listOf("查一下", "资料", "政策", "标准", "竞品", "公司", "产品", "模型", "论文", "法规", "官网")),
        IntentRule("信息补充", "Low", "这段主要是在补充背景，先记录，暂不打断。", listOf("背景", "情况是", "因为", "原因", "目前", "现在")),
    )

    fun analyze(text: String): IntentAnalysis {
        val compact = text.trim()
        val matched = patterns
            .mapNotNull { rule ->
                val hits = rule.keywords.filter { compact.contains(it, ignoreCase = true) }
                if (hits.isEmpty()) null else IntentMatch(rule, hits)
            }
            .maxWithOrNull(
                compareBy<IntentMatch> { priorityWeight(it.rule.priority) + it.hits.size * 2 }
                    .thenBy { it.hits.sumOf(String::length) },
            )

        if (matched != null) {
            val rule = matched.rule
            val evidence = matched.hits.take(3).joinToString("、")
            val confidence = (0.58f + matched.hits.size.coerceAtMost(4) * 0.07f + evidence.length.coerceAtMost(10) * 0.01f)
                .coerceAtMost(0.90f)
            return IntentAnalysis(
                label = rule.label,
                confidence = confidence,
                evidence = evidence,
                priority = rule.priority,
                suggestedHandling = rule.handling,
            )
        }

        val questionLike = compact.endsWith("吗") || compact.endsWith("呢") || compact.contains("？") || compact.contains("?")
        if (questionLike) {
            return IntentAnalysis(
                label = "提问澄清",
                confidence = 0.58f,
                evidence = compact.take(40),
                priority = "Medium",
                suggestedHandling = "对方在提问，建议先识别问题所需信息再回答。",
            )
        }

        return IntentAnalysis(
            label = "信息陈述",
            confidence = 0.42f,
            evidence = compact.take(40),
            priority = "Low",
            suggestedHandling = "这段更像普通信息陈述，先记录进上下文。",
        )
    }

    private data class IntentRule(
        val label: String,
        val priority: String,
        val handling: String,
        val keywords: List<String>,
    )

    private data class IntentMatch(
        val rule: IntentRule,
        val hits: List<String>,
    )

    private fun priorityWeight(priority: String): Int = when (priority) {
        "High" -> 10
        "Medium" -> 5
        else -> 1
    }
}
