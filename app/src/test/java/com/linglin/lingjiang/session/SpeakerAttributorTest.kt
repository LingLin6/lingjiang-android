package com.linglin.lingjiang.session

import com.linglin.lingjiang.audio.AudioChunk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerAttributorTest {
    @Test
    fun separatesSpeakersWhenPitchAndVoicingAreStable() {
        val attributor = SpeakerAttributor()

        val first = attributor.assign(chunk(start = 0, end = 1_000, pitchHz = 118.0), "这个方案先这样定")
        val second = attributor.assign(chunk(start = 3_000, end = 4_000, pitchHz = 230.0), "验收标准需要重新确认")

        assertEquals("说话人1", first.label)
        assertEquals("说话人2", second.label)
        assertTrue(second.confidence in 0.36f..0.64f)
    }

    @Test
    fun doesNotCreateNewSpeakerFromWeakUnvoicedAudio() {
        val attributor = SpeakerAttributor()

        attributor.assign(chunk(start = 0, end = 1_000, pitchHz = 135.0), "供应商下周给报价")
        val weak = attributor.assign(
            chunk(
                start = 4_000,
                end = 4_900,
                pitchHz = 0.0,
                voicedRatio = 0.02,
                speechMillis = 120,
                strongSpeechMillis = 30,
            ),
            "背景里有一点声音",
        )

        assertEquals("说话人1", weak.label)
        assertEquals(0.28f, weak.confidence, 0.0001f)
    }

    private fun chunk(
        start: Long,
        end: Long,
        pitchHz: Double,
        voicedRatio: Double = 0.62,
        speechMillis: Long = 720,
        strongSpeechMillis: Long = 420,
    ): AudioChunk = AudioChunk(
        file = File.createTempFile("speaker-attributor", ".wav").apply { deleteOnExit() },
        startedAtMillis = start,
        endedAtMillis = end,
        rms = 620.0,
        peak = 2_400,
        activeRatio = 0.12,
        isFinal = true,
        sequence = end,
        sessionId = "test-session",
        segmentRms = 620.0,
        segmentPeak = 2_400,
        segmentActiveRatio = 0.12,
        speechMillis = speechMillis,
        strongSpeechMillis = strongSpeechMillis,
        speechScore = 0.9,
        isSpeechLikely = true,
        segmentZeroCrossingRate = 0.045,
        segmentPeakToRms = 3.87,
        segmentPitchHz = pitchHz,
        segmentVoicedRatio = voicedRatio,
    )
}
