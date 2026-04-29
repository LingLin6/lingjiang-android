package com.linglin.lingjiang.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectralNoiseReducerTest {
    @Test
    fun preservesPcmLengthAndBounds() {
        val pcm = toneWithNoise(durationMillis = 1_000)

        val reduced = SpectralNoiseReducer.reduceNoise(pcm, sampleRate = 16_000)

        assertEquals(pcm.size, reduced.size)
        var index = 0
        while (index + 1 < reduced.size) {
            val sample = ((reduced[index + 1].toInt() shl 8) or (reduced[index].toInt() and 0xff)).toShort().toInt()
            assertTrue(sample in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
            index += 2
        }
    }

    @Test
    fun keepsAudibleEnergy() {
        val pcm = toneWithNoise(durationMillis = 1_000)

        val reduced = SpectralNoiseReducer.reduceNoise(pcm, sampleRate = 16_000)

        assertTrue(rms(reduced) > 80.0)
    }

    private fun toneWithNoise(durationMillis: Int): ByteArray {
        val sampleRate = 16_000
        val count = sampleRate * durationMillis / 1000
        val pcm = ByteArray(count * 2)
        var i = 0
        while (i < count) {
            val t = i.toDouble() / sampleRate
            val tone = 900.0 * sin(2.0 * PI * 220.0 * t)
            val noise = if (i % 37 < 18) 180.0 else -180.0
            val sample = (tone + noise).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val byteIndex = i * 2
            pcm[byteIndex] = (sample and 0xff).toByte()
            pcm[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
            i++
        }
        return pcm
    }

    private fun rms(pcm: ByteArray): Double {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xff)).toShort().toInt()
            sum += sample * sample.toDouble()
            count++
            index += 2
        }
        return kotlin.math.sqrt(sum / count.coerceAtLeast(1))
    }
}
