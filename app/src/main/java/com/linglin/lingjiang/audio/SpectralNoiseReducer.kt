package com.linglin.lingjiang.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object SpectralNoiseReducer {
    fun reduceNoise(
        pcm: ByteArray,
        sampleRate: Int,
        strength: Double = DEFAULT_STRENGTH,
        spectralFloor: Double = DEFAULT_SPECTRAL_FLOOR,
    ): ByteArray {
        if (sampleRate <= 0 || pcm.size < FFT_SIZE * 2) return pcm
        val samples = pcmToDouble(pcm)
        if (samples.size < FFT_SIZE) return pcm

        removeDc(samples)
        val frameCount = 1 + ((samples.size - 1) / HOP_SIZE)
        val spectra = Array(frameCount) { Spectrum(DoubleArray(BINS), DoubleArray(BINS), DoubleArray(BINS)) }
        val frame = DoubleArray(FFT_SIZE)
        val window = hannWindow()

        var frameIndex = 0
        var start = 0
        while (frameIndex < frameCount) {
            java.util.Arrays.fill(frame, 0.0)
            var i = 0
            while (i < FFT_SIZE) {
                val sourceIndex = start + i
                if (sourceIndex < samples.size) {
                    frame[i] = samples[sourceIndex] * window[i]
                }
                i++
            }
            val real = frame.copyOf()
            val imag = DoubleArray(FFT_SIZE)
            fft(real, imag, inverse = false)
            var bin = 0
            while (bin < BINS) {
                val re = real[bin]
                val im = imag[bin]
                spectra[frameIndex].real[bin] = re
                spectra[frameIndex].imag[bin] = im
                spectra[frameIndex].mag[bin] = kotlin.math.sqrt(re * re + im * im)
                bin++
            }
            frameIndex++
            start += HOP_SIZE
        }

        val noise = estimateNoise(spectra)
        val output = DoubleArray(samples.size + FFT_SIZE)
        val weights = DoubleArray(output.size)
        frameIndex = 0
        start = 0
        while (frameIndex < frameCount) {
            val real = DoubleArray(FFT_SIZE)
            val imag = DoubleArray(FFT_SIZE)
            val spectrum = spectra[frameIndex]
            var bin = 0
            while (bin < BINS) {
                val mag = spectrum.mag[bin]
                val cleanMag = maxOf(mag - strength * noise[bin], spectralFloor * mag)
                val mask = if (mag <= 1e-8) 0.0 else (cleanMag / mag).pow(MASK_EXPONENT)
                real[bin] = spectrum.real[bin] * mask
                imag[bin] = spectrum.imag[bin] * mask
                if (bin != 0 && bin != FFT_SIZE / 2) {
                    val mirror = FFT_SIZE - bin
                    real[mirror] = real[bin]
                    imag[mirror] = -imag[bin]
                }
                bin++
            }
            fft(real, imag, inverse = true)
            var i = 0
            while (i < FFT_SIZE) {
                val outIndex = start + i
                if (outIndex < output.size) {
                    val w = window[i]
                    output[outIndex] += real[i] * w
                    weights[outIndex] += w * w
                }
                i++
            }
            frameIndex++
            start += HOP_SIZE
        }

        val reduced = ByteArray(pcm.size)
        var i = 0
        while (i < samples.size) {
            val value = if (weights[i] > 1e-9) output[i] / weights[i] else output[i]
            val sample = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val byteIndex = i * 2
            reduced[byteIndex] = (sample and 0xff).toByte()
            reduced[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
            i++
        }
        return reduced
    }

    private data class Spectrum(
        val real: DoubleArray,
        val imag: DoubleArray,
        val mag: DoubleArray,
    )

    private fun estimateNoise(spectra: Array<Spectrum>): DoubleArray {
        val noise = DoubleArray(BINS)
        val values = DoubleArray(spectra.size)
        var bin = 0
        while (bin < BINS) {
            var i = 0
            while (i < spectra.size) {
                values[i] = spectra[i].mag[bin]
                i++
            }
            values.sort()
            val index = ((values.size - 1) * NOISE_PERCENTILE).toInt().coerceIn(0, values.lastIndex)
            noise[bin] = values[index]
            bin++
        }
        return noise
    }

    private fun pcmToDouble(pcm: ByteArray): DoubleArray {
        val samples = DoubleArray(pcm.size / 2)
        var byteIndex = 0
        var sampleIndex = 0
        while (sampleIndex < samples.size && byteIndex + 1 < pcm.size) {
            samples[sampleIndex] = ((pcm[byteIndex + 1].toInt() shl 8) or (pcm[byteIndex].toInt() and 0xff))
                .toShort()
                .toDouble()
            byteIndex += 2
            sampleIndex++
        }
        return samples
    }

    private fun removeDc(samples: DoubleArray) {
        if (samples.isEmpty()) return
        var sum = 0.0
        samples.forEach { sum += it }
        val mean = sum / samples.size
        var i = 0
        while (i < samples.size) {
            samples[i] -= mean
            i++
        }
    }

    private fun hannWindow(): DoubleArray {
        val window = DoubleArray(FFT_SIZE)
        var i = 0
        while (i < FFT_SIZE) {
            window[i] = 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
            i++
        }
        return window
    }

    private fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        var i = 1
        while (i < n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmpRe = real[i]
                real[i] = real[j]
                real[j] = tmpRe
                val tmpIm = imag[i]
                imag[i] = imag[j]
                imag[j] = tmpIm
            }
            i++
        }

        var length = 2
        while (length <= n) {
            val angle = 2.0 * PI / length * if (inverse) 1.0 else -1.0
            val wLenRe = cos(angle)
            val wLenIm = sin(angle)
            var start = 0
            while (start < n) {
                var wRe = 1.0
                var wIm = 0.0
                var k = 0
                val half = length / 2
                while (k < half) {
                    val evenRe = real[start + k]
                    val evenIm = imag[start + k]
                    val oddRe = real[start + k + half] * wRe - imag[start + k + half] * wIm
                    val oddIm = real[start + k + half] * wIm + imag[start + k + half] * wRe
                    real[start + k] = evenRe + oddRe
                    imag[start + k] = evenIm + oddIm
                    real[start + k + half] = evenRe - oddRe
                    imag[start + k + half] = evenIm - oddIm
                    val nextRe = wRe * wLenRe - wIm * wLenIm
                    val nextIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextRe
                    wIm = nextIm
                    k++
                }
                start += length
            }
            length = length shl 1
        }

        if (inverse) {
            var scaleIndex = 0
            while (scaleIndex < n) {
                real[scaleIndex] /= n.toDouble()
                imag[scaleIndex] /= n.toDouble()
                scaleIndex++
            }
        }
    }

    private const val FFT_SIZE = 512
    private const val HOP_SIZE = 128
    private const val BINS = FFT_SIZE / 2 + 1
    private const val NOISE_PERCENTILE = 0.20
    private const val MASK_EXPONENT = 0.85
    private const val DEFAULT_STRENGTH = 0.95
    private const val DEFAULT_SPECTRAL_FLOOR = 0.10
}
