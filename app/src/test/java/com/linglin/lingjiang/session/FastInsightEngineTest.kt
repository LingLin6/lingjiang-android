package com.linglin.lingjiang.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastInsightEngineTest {
    @Test
    fun producesFastActionableHintsForLingLinWork() {
        val result = FastInsightEngine.analyze(
            currentText = "供应商说接口延迟不稳定，报价下周一前给，我们这边需要确认验收标准",
            rollingSummary = "",
        )

        assertNotNull(result)
        result!!
        assertTrue(result.insights.any { it.priority == "High" })
        assertTrue(result.insights.any { it.text.contains("指标") || it.text.contains("验收") || it.text.contains("报价") })
        assertFalse(result.researchQueries.isEmpty())
    }
}
