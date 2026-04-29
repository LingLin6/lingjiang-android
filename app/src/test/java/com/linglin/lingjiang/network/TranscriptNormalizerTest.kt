package com.linglin.lingjiang.network

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptNormalizerTest {
    @Test
    fun normalizesRoboticsAndControlTerms() {
        val text = "模型预测控制的mcp让机线臂通过刘局和齿纹提高齿力量"

        assertEquals(
            "模型预测控制的MPC让机械臂通过扭矩和齿轮提高齿轮力量",
            TranscriptNormalizer.normalize(text),
        )
    }

    @Test
    fun collapsesOnlyLowValueFillers() {
        assertEquals("嗯这个方案好的", TranscriptNormalizer.normalize("嗯嗯这个方案好的好的"))
    }

    @Test
    fun normalizesConservativeReducerTerms() {
        assertEquals("谐波减速器和谐波减速器", TranscriptNormalizer.normalize("斜波减速器和斜过减速器"))
    }
}
