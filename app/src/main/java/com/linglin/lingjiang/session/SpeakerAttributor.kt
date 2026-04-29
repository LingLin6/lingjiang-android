package com.linglin.lingjiang.session

import com.linglin.lingjiang.audio.AudioChunk
import kotlin.math.abs

data class SpeakerAttribution(
    val label: String,
    val confidence: Float,
    val turnIndex: Int,
)

class SpeakerAttributor {
    private val profiles = mutableListOf<SpeakerProfile>()
    private var lastEndMillis = 0L
    private var turnIndex = 0

    fun reset() {
        profiles.clear()
        lastEndMillis = 0L
        turnIndex = 0
    }

    fun assign(chunk: AudioChunk, text: String): SpeakerAttribution {
        val feature = SpeakerFeature.from(chunk)
        val gapMillis = if (lastEndMillis == 0L) 0L else chunk.startedAtMillis - lastEndMillis
        lastEndMillis = chunk.endedAtMillis

        if (profiles.isEmpty()) {
            profiles += SpeakerProfile(label = "说话人1", feature = feature)
            turnIndex++
            return SpeakerAttribution(
                label = "说话人1",
                confidence = initialConfidence(feature),
                turnIndex = turnIndex,
            )
        }

        val best = profiles.minBy { it.distance(feature) }
        val bestDistance = best.distance(feature)
        val shouldStartNewSpeaker = gapMillis >= NEW_TURN_GAP_MS &&
            text.length >= MIN_TEXT_FOR_NEW_SPEAKER &&
            feature.supportsSpeakerSplit &&
            (bestDistance >= NEW_SPEAKER_DISTANCE || best.pitchDistance(feature) >= NEW_SPEAKER_PITCH_DISTANCE) &&
            profiles.size < MAX_SPEAKERS

        val profile = if (shouldStartNewSpeaker) {
            SpeakerProfile(label = "说话人${profiles.size + 1}", feature = feature).also { profiles += it }
        } else {
            best.also {
                if (feature.supportsProfileUpdate) {
                    it.update(feature)
                }
            }
        }

        if (gapMillis >= NEW_TURN_GAP_MS) turnIndex++
        val confidence = confidenceFor(feature, bestDistance, shouldStartNewSpeaker)
        return SpeakerAttribution(label = profile.label, confidence = confidence, turnIndex = turnIndex)
    }

    private fun initialConfidence(feature: SpeakerFeature): Float =
        if (feature.supportsProfileUpdate) MEDIUM_LOW_CONFIDENCE else LOW_CONFIDENCE

    private fun confidenceFor(feature: SpeakerFeature, distance: Double, isNewSpeaker: Boolean): Float {
        if (!feature.supportsProfileUpdate) return LOW_CONFIDENCE
        val base = if (isNewSpeaker) 0.40 else 0.50
        val voicedBonus = (feature.voicedRatio * 0.18).coerceIn(0.0, 0.12)
        val distancePenalty = (distance * 0.24).coerceIn(0.0, 0.18)
        return (base + voicedBonus - distancePenalty)
            .coerceIn(MEDIUM_LOW_CONFIDENCE.toDouble(), MAX_CONFIDENCE.toDouble())
            .toFloat()
    }

    companion object {
        private const val MAX_SPEAKERS = 4
        private const val NEW_TURN_GAP_MS = 1_500L
        private const val NEW_SPEAKER_DISTANCE = 0.36
        private const val NEW_SPEAKER_PITCH_DISTANCE = 0.42
        private const val MIN_TEXT_FOR_NEW_SPEAKER = 4
        private const val LOW_CONFIDENCE = 0.28f
        private const val MEDIUM_LOW_CONFIDENCE = 0.36f
        private const val MAX_CONFIDENCE = 0.64f
    }
}

private data class SpeakerFeature(
    val rms: Double,
    val peakToRms: Double,
    val activeRatio: Double,
    val zeroCrossingRate: Double,
    val pitch: Double,
    val voicedRatio: Double,
    val speechSeconds: Double,
    val strongSpeechSeconds: Double,
    val hasPitch: Boolean,
) {
    val supportsSpeakerSplit: Boolean
        get() = hasPitch && voicedRatio >= 0.18 && speechSeconds >= 0.36 && strongSpeechSeconds >= 0.18

    val supportsProfileUpdate: Boolean
        get() = voicedRatio >= 0.10 && speechSeconds >= 0.18 && strongSpeechSeconds >= 0.09

    companion object {
        private const val REFERENCE_PITCH_HZ = 140.0

        fun from(chunk: AudioChunk): SpeakerFeature = SpeakerFeature(
            rms = (chunk.segmentRms / 800.0).coerceIn(0.0, 3.0),
            peakToRms = (chunk.segmentPeakToRms / 8.0).coerceIn(0.0, 2.0),
            activeRatio = (chunk.segmentActiveRatio * 16.0).coerceIn(0.0, 2.0),
            zeroCrossingRate = (chunk.segmentZeroCrossingRate * 8.0).coerceIn(0.0, 2.0),
            pitch = (if (chunk.segmentPitchHz > 0.0) {
                kotlin.math.ln(chunk.segmentPitchHz / REFERENCE_PITCH_HZ) / kotlin.math.ln(2.0)
            } else {
                0.0
            }).coerceIn(-1.2, 1.4),
            voicedRatio = chunk.segmentVoicedRatio.coerceIn(0.0, 1.0),
            speechSeconds = chunk.speechMillis / 1000.0,
            strongSpeechSeconds = chunk.strongSpeechMillis / 1000.0,
            hasPitch = chunk.segmentPitchHz > 0.0,
        )
    }
}

private class SpeakerProfile(
    val label: String,
    feature: SpeakerFeature,
) {
    private var centroid = feature
    private var count = 1

    fun distance(feature: SpeakerFeature): Double =
        0.22 * abs(centroid.rms - feature.rms) +
            0.14 * abs(centroid.peakToRms - feature.peakToRms) +
            0.14 * abs(centroid.activeRatio - feature.activeRatio) +
            0.14 * abs(centroid.zeroCrossingRate - feature.zeroCrossingRate) +
            0.36 * pitchDistance(feature)

    fun pitchDistance(feature: SpeakerFeature): Double =
        if (centroid.hasPitch && feature.hasPitch) {
            abs(centroid.pitch - feature.pitch)
        } else {
            0.18
        }

    fun update(feature: SpeakerFeature) {
        val weight = 1.0 / (count + 1).coerceAtMost(6)
        centroid = SpeakerFeature(
            rms = centroid.rms * (1 - weight) + feature.rms * weight,
            peakToRms = centroid.peakToRms * (1 - weight) + feature.peakToRms * weight,
            activeRatio = centroid.activeRatio * (1 - weight) + feature.activeRatio * weight,
            zeroCrossingRate = centroid.zeroCrossingRate * (1 - weight) + feature.zeroCrossingRate * weight,
            pitch = if (centroid.hasPitch && feature.hasPitch) {
                centroid.pitch * (1 - weight) + feature.pitch * weight
            } else if (feature.hasPitch) {
                feature.pitch
            } else {
                centroid.pitch
            },
            voicedRatio = centroid.voicedRatio * (1 - weight) + feature.voicedRatio * weight,
            speechSeconds = centroid.speechSeconds * (1 - weight) + feature.speechSeconds * weight,
            strongSpeechSeconds = centroid.strongSpeechSeconds * (1 - weight) + feature.strongSpeechSeconds * weight,
            hasPitch = centroid.hasPitch || feature.hasPitch,
        )
        count++
    }
}
