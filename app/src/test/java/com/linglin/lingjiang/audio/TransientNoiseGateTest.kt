package com.linglin.lingjiang.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientNoiseGateTest {
    @Test
    fun rejectsShortImpactBurstWithoutVoicing() {
        val metrics = TransientNoiseGate.analyze(
            frameRmsValues = listOf(18.0, 24.0, 1_800.0, 1_250.0, 420.0, 35.0, 20.0, 18.0, 16.0, 14.0),
            strongRuns = listOf(2),
            frameMillis = 30L,
            durationMillis = 900L,
            speechMillis = 180L,
            strongSpeechMillis = 90L,
            peakToRms = 12.4,
            voicedRatio = 0.0,
            pitchHz = 0.0,
        )

        assertTrue(metrics.isLikelyImpact)
    }

    @Test
    fun rejectsRepeatedImpactTrainWithoutVoicing() {
        val frameRms = buildList {
            repeat(20) {
                add(24.0)
                add(1_650.0)
                add(220.0)
                add(32.0)
                add(18.0)
            }
        }
        val metrics = TransientNoiseGate.analyze(
            frameRmsValues = frameRms,
            strongRuns = List(20) { 1 },
            frameMillis = 30L,
            durationMillis = 3_000L,
            speechMillis = 720L,
            strongSpeechMillis = 600L,
            peakToRms = 10.8,
            voicedRatio = 0.0,
            pitchHz = 0.0,
        )

        assertTrue(metrics.isLikelyImpact)
    }

    @Test
    fun keepsSustainedVoicedSpeech() {
        val metrics = TransientNoiseGate.analyze(
            frameRmsValues = listOf(180.0, 280.0, 520.0, 760.0, 840.0, 790.0, 680.0, 610.0, 540.0, 360.0),
            strongRuns = listOf(7),
            frameMillis = 30L,
            durationMillis = 900L,
            speechMillis = 720L,
            strongSpeechMillis = 510L,
            peakToRms = 4.1,
            voicedRatio = 0.36,
            pitchHz = 145.0,
        )

        assertFalse(metrics.isLikelyImpact)
    }

    @Test
    fun rejectsSingleStartupImpactFrame() {
        val isImpact = TransientNoiseGate.isStartupImpactFrame(
            rms = 760.0,
            peak = 10_800,
            activeRatio = 0.05,
            zeroCrossingRate = 0.04,
            noiseFloor = 45.0,
        )

        assertTrue(isImpact)
    }
}
