package com.linglin.lingjiang.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {
    @Test
    fun forceAlwaysTriggersAnalysis() {
        assertTrue(EventDetector.shouldAnalyze(text = "", millisSinceLastAnalysis = 0L, force = true))
    }

    @Test
    fun keywordTriggersAfterCooldown() {
        assertTrue(EventDetector.shouldAnalyze(text = "这个风险需要你今天确认", millisSinceLastAnalysis = 6_000L))
    }

    @Test
    fun quietShortTextDoesNotTrigger() {
        assertFalse(EventDetector.shouldAnalyze(text = "好的", millisSinceLastAnalysis = 60_000L))
    }

    @Test
    fun lingLinWorkKeywordTriggersQuickly() {
        assertTrue(EventDetector.shouldAnalyze(text = "这个接口方案需要确认验收标准", millisSinceLastAnalysis = 3_000L))
    }
}
