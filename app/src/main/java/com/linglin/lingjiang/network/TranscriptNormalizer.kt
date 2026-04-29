package com.linglin.lingjiang.network

object TranscriptNormalizer {
    private val replacements = listOf(
        "mcp" to "MPC",
        "Mcp" to "MPC",
        "机械币" to "机械臂",
        "机线臂" to "机械臂",
        "基线臂" to "机械臂",
        "机械力" to "机械臂",
        "流矩" to "扭矩",
        "刘矩" to "扭矩",
        "刘局" to "扭矩",
        "扭局" to "扭矩",
        "为矩" to "扭矩",
        "齿纹" to "齿轮",
        "齿力量" to "齿轮力量",
        "执行气" to "执行器",
        "制气" to "执行器",
        "斜部减速器" to "谐波减速器",
        "斜波减速器" to "谐波减速器",
        "斜过减速器" to "谐波减速器",
        "协波减速器" to "谐波减速器",
    )

    fun normalize(text: String): String {
        var value = text.replace(" ", "").trim()
        replacements.forEach { (from, to) ->
            value = value.replace(from, to, ignoreCase = false)
        }
        value = collapseLowValueRuns(value)
        return value
    }

    private fun collapseLowValueRuns(text: String): String =
        text.replace(Regex("([嗯啊呃哦诶])\\1+"), "$1")
            .replace("好的好的", "好的")
            .replace("谢谢谢", "谢谢")
}
