package com.linglin.lingjiang.audio

internal data class TransientNoiseMetrics(
    val rmsModulation: Double,
    val isolatedStrongRatio: Double,
    val maxStrongRunMillis: Long,
    val strongRunCount: Int,
    val impulseScore: Double,
    val isLikelyImpact: Boolean,
)

internal object TransientNoiseGate {
    fun analyze(
        frameRmsValues: List<Double>,
        strongRuns: List<Int>,
        frameMillis: Long,
        durationMillis: Long,
        speechMillis: Long,
        strongSpeechMillis: Long,
        peakToRms: Double,
        voicedRatio: Double,
        pitchHz: Double,
    ): TransientNoiseMetrics {
        val modulation = rmsModulation(frameRmsValues)
        val strongFrameCount = strongRuns.sum()
        val maxStrongRunFrames = strongRuns.maxOrNull() ?: 0
        val maxStrongRunMillis = maxStrongRunFrames * frameMillis
        val isolatedStrongFrames = strongRuns.filter { it <= ISOLATED_STRONG_RUN_FRAMES }.sum()
        val isolatedStrongRatio = if (strongFrameCount == 0) {
            0.0
        } else {
            isolatedStrongFrames.toDouble() / strongFrameCount.toDouble()
        }
        val speechDensity = speechMillis.toDouble() / durationMillis.coerceAtLeast(1L).toDouble()
        val strongDensity = strongSpeechMillis.toDouble() / durationMillis.coerceAtLeast(1L).toDouble()
        val weakVoicing = voicedRatio < IMPACT_MAX_VOICED_RATIO ||
            (voicedRatio < BORDERLINE_VOICED_RATIO && pitchHz <= 0.0)
        val peakScore = ((peakToRms - IMPACT_PEAK_TO_RMS_START) / 7.0).coerceIn(0.0, 1.0)
        val modulationScore = ((modulation - IMPACT_MODULATION_START) / 0.85).coerceIn(0.0, 1.0)
        val isolationScore = isolatedStrongRatio.coerceIn(0.0, 1.0)
        val shortRunScore = (1.0 - maxStrongRunMillis.toDouble() / SUSTAINED_STRONG_RUN_MS).coerceIn(0.0, 1.0)
        val lowDensityScore = (1.0 - speechDensity / SUSTAINED_SPEECH_DENSITY).coerceIn(0.0, 1.0)
        val weakVoicingScore = (1.0 - voicedRatio / BORDERLINE_VOICED_RATIO).coerceIn(0.0, 1.0)
        val impulseScore = (
            0.26 * peakScore +
                0.24 * modulationScore +
                0.18 * isolationScore +
                0.16 * shortRunScore +
                0.10 * lowDensityScore +
                0.06 * weakVoicingScore
            ).coerceIn(0.0, 1.0)

        val burstyShape = modulation >= IMPACT_MODULATION_START &&
            (
                isolatedStrongRatio >= IMPACT_ISOLATED_STRONG_RATIO ||
                    maxStrongRunMillis <= SHORT_STRONG_RUN_MS ||
                    strongDensity <= LOW_STRONG_DENSITY
                )
        val thinSegment = speechDensity <= LOW_SPEECH_DENSITY &&
            strongSpeechMillis <= MAX_IMPACT_STRONG_MS
        val likelyImpact = (
            weakVoicing &&
                peakToRms >= IMPACT_PEAK_TO_RMS &&
                burstyShape &&
                thinSegment
            ) ||
            (
                weakVoicing &&
                    peakToRms >= HARD_IMPACT_PEAK_TO_RMS &&
                    modulation >= HARD_IMPACT_MODULATION &&
                    strongSpeechMillis <= HARD_IMPACT_STRONG_MS
                ) ||
            (
                voicedRatio < VERY_LOW_VOICED_RATIO &&
                    peakToRms >= BORDERLINE_IMPACT_PEAK_TO_RMS &&
                    maxStrongRunMillis <= VERY_SHORT_STRONG_RUN_MS &&
                    speechMillis <= VERY_SHORT_SPEECH_MS
                ) ||
            impulseScore >= IMPACT_SCORE_REJECT

        return TransientNoiseMetrics(
            rmsModulation = modulation,
            isolatedStrongRatio = isolatedStrongRatio,
            maxStrongRunMillis = maxStrongRunMillis,
            strongRunCount = strongRuns.size,
            impulseScore = impulseScore,
            isLikelyImpact = likelyImpact,
        )
    }

    fun isStartupImpactFrame(
        rms: Double,
        peak: Int,
        activeRatio: Double,
        zeroCrossingRate: Double,
        noiseFloor: Double,
    ): Boolean {
        if (rms <= 0.0) return false
        val peakToRms = peak / rms
        val loudAgainstFloor = rms >= noiseFloor.coerceAtLeast(1.0) * STARTUP_FLOOR_MULTIPLIER
        return loudAgainstFloor &&
            peakToRms >= STARTUP_IMPACT_PEAK_TO_RMS &&
            activeRatio <= STARTUP_IMPACT_MAX_ACTIVE_RATIO &&
            zeroCrossingRate <= STARTUP_IMPACT_MAX_ZCR
    }

    private fun rmsModulation(values: List<Double>): Double {
        if (values.size < 2) return 1.0
        val mean = values.average()
        if (mean <= 0.0) return 1.0
        val variance = values.fold(0.0) { acc, value ->
            val delta = value - mean
            acc + delta * delta
        } / values.size.toDouble()
        return kotlin.math.sqrt(variance) / mean.coerceAtLeast(1.0)
    }

    private const val ISOLATED_STRONG_RUN_FRAMES = 2
    private const val IMPACT_MAX_VOICED_RATIO = 0.12
    private const val BORDERLINE_VOICED_RATIO = 0.18
    private const val VERY_LOW_VOICED_RATIO = 0.08
    private const val IMPACT_PEAK_TO_RMS_START = 6.2
    private const val IMPACT_MODULATION_START = 0.72
    private const val IMPACT_PEAK_TO_RMS = 8.2
    private const val HARD_IMPACT_PEAK_TO_RMS = 11.5
    private const val HARD_IMPACT_MODULATION = 0.60
    private const val BORDERLINE_IMPACT_PEAK_TO_RMS = 7.2
    private const val IMPACT_ISOLATED_STRONG_RATIO = 0.45
    private const val SHORT_STRONG_RUN_MS = 210L
    private const val VERY_SHORT_STRONG_RUN_MS = 150L
    private const val SUSTAINED_STRONG_RUN_MS = 420.0
    private const val SUSTAINED_SPEECH_DENSITY = 0.56
    private const val LOW_SPEECH_DENSITY = 0.42
    private const val LOW_STRONG_DENSITY = 0.24
    private const val MAX_IMPACT_STRONG_MS = 390L
    private const val HARD_IMPACT_STRONG_MS = 450L
    private const val VERY_SHORT_SPEECH_MS = 360L
    private const val IMPACT_SCORE_REJECT = 0.68
    private const val STARTUP_FLOOR_MULTIPLIER = 4.0
    private const val STARTUP_IMPACT_PEAK_TO_RMS = 9.5
    private const val STARTUP_IMPACT_MAX_ACTIVE_RATIO = 0.16
    private const val STARTUP_IMPACT_MAX_ZCR = 0.22
}
