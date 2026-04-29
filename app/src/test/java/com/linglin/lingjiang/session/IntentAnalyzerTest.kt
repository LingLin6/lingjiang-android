package com.linglin.lingjiang.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentAnalyzerTest {
    @Test
    fun detectsTechnicalDecisionContext() {
        val result = IntentAnalyzer.analyze("这个接口延迟太高，部署方案要不要换模型")

        assertEquals("技术方案判断", result.label)
        assertEquals("High", result.priority)
        assertTrue(result.confidence >= 0.70f)
    }

    @Test
    fun detectsAcceptanceBoundary() {
        val result = IntentAnalyzer.analyze("验收标准和接口口径今天需要确认")

        assertEquals("验收边界", result.label)
        assertEquals("High", result.priority)
    }
}
