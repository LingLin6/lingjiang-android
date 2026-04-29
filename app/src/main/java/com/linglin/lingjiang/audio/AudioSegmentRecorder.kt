package com.linglin.lingjiang.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class AudioChunk(
    val file: File,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val rms: Double,
    val peak: Int,
    val activeRatio: Double,
    val isFinal: Boolean,
    val sequence: Long,
    val sessionId: String,
    val segmentRms: Double = rms,
    val segmentPeak: Int = peak,
    val segmentActiveRatio: Double = activeRatio,
    val speechMillis: Long = 0L,
    val strongSpeechMillis: Long = 0L,
    val speechScore: Double = 1.0,
    val isSpeechLikely: Boolean = true,
    val segmentZeroCrossingRate: Double = 0.0,
    val segmentPeakToRms: Double = 0.0,
    val segmentPitchHz: Double = 0.0,
    val segmentVoicedRatio: Double = 0.0,
    val segmentRmsModulation: Double = 0.0,
    val segmentImpulseScore: Double = 0.0,
    val isImpulsiveNoise: Boolean = false,
)

data class AudioLevel(
    val rms: Double,
    val peak: Int,
    val activeRatio: Double,
    val isSpeech: Boolean,
    val timestampMillis: Long = System.currentTimeMillis(),
)

class AudioSegmentRecorder(private val context: Context) {
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        scope: CoroutineScope,
        sessionId: String,
        onChunk: suspend (AudioChunk) -> Unit,
        onLevel: (AudioLevel) -> Unit = {},
        onError: suspend (String) -> Unit,
    ) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                recordLoop(scope, sessionId, onChunk, onLevel)
            }.onFailure { throwable ->
                onError(throwable.message ?: "录音启动失败")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordLoop(
        scope: CoroutineScope,
        sessionId: String,
        onChunk: suspend (AudioChunk) -> Unit,
        onLevel: (AudioLevel) -> Unit,
    ) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_BYTES * 4)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        audioRecord = recorder
        recorder.startRecording()

        val chunkDir = File(context.cacheDir, "audio_chunks/$sessionId").apply { mkdirs() }
        val readBuffer = ByteArray(FRAME_BYTES)
        val preroll = ArrayDeque<ByteArray>()
        var activePcm = ByteArrayOutputStream()
        var pendingPcm = ByteArrayOutputStream()
        var inSpeech = false
        var pendingSpeechFrames = 0
        var silenceFrames = 0
        var activeStartMillis = 0L
        var pendingStartMillis = 0L
        var lastPartialMillis = 0L
        var sequence = 0L
        var submittedChunksInSegment = 0
        var noiseFloor = INITIAL_NOISE_FLOOR
        var recordingStartedAt = 0L

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val read = recorder.read(readBuffer, 0, readBuffer.size)
            if (read <= 0) continue

            val frame = if (read == FRAME_BYTES) readBuffer.copyOf() else readBuffer.copyOf(read)
            val stats = audioStats(frame, frame.size)
            val now = System.currentTimeMillis()
            if (recordingStartedAt == 0L) recordingStartedAt = now
            val isCalibratingNoise = now - recordingStartedAt < NOISE_CALIBRATION_MS
            val isSpeech = !isCalibratingNoise && isSpeechFrame(stats, inSpeech, noiseFloor)
            if (!inSpeech) {
                noiseFloor = updateNoiseFloor(
                    currentFloor = noiseFloor,
                    frameRms = stats.rms,
                    isCalibrating = isCalibratingNoise,
                    acceptedAsSpeech = isSpeech,
                )
            }
            onLevel(
                AudioLevel(
                    rms = stats.rms,
                    peak = stats.peak,
                    activeRatio = stats.activeRatio,
                    isSpeech = isSpeech,
                ),
            )

            if (!inSpeech) {
                preroll.addLast(frame)
                while (preroll.size > PREROLL_FRAMES) {
                    preroll.removeFirst()
                }
                pendingSpeechFrames = if (isSpeech) pendingSpeechFrames + 1 else 0
                if (pendingSpeechFrames >= START_SPEECH_FRAMES) {
                    inSpeech = true
                    silenceFrames = 0
                    activePcm = ByteArrayOutputStream()
                    pendingPcm = ByteArrayOutputStream()
                    preroll.forEach {
                        activePcm.write(it)
                        pendingPcm.write(it)
                    }
                    activeStartMillis = now - (preroll.size * FRAME_MS)
                    pendingStartMillis = activeStartMillis
                    lastPartialMillis = 0L
                    submittedChunksInSegment = 0
                    preroll.clear()
                    pendingSpeechFrames = 0
                }
                continue
            }

            activePcm.write(frame)
            pendingPcm.write(frame)
            silenceFrames = if (isSpeech) 0 else silenceFrames + 1
            val activeDurationMillis = now - activeStartMillis

            if (activeDurationMillis >= MIN_PARTIAL_MS &&
                now - lastPartialMillis >= PARTIAL_INTERVAL_MS &&
                pendingPcm.size() >= MIN_PARTIAL_BYTES
            ) {
                val chunk = writeChunk(
                    chunkDir = chunkDir,
                    pcmBytes = pendingPcm.toByteArray(),
                    startedAtMillis = pendingStartMillis,
                    endedAtMillis = now,
                    isFinal = false,
                    sequence = ++sequence,
                    sessionId = sessionId,
                    allowControlChunk = false,
                    segmentQuality = null,
                    forceControlChunk = false,
                )
                if (chunk != null) {
                    scope.launch(Dispatchers.IO) { onChunk(chunk) }
                    submittedChunksInSegment++
                    lastPartialMillis = now
                    pendingStartMillis = now
                    pendingPcm = ByteArrayOutputStream()
                }
            }

            val shouldFinish = silenceFrames * FRAME_MS >= SILENCE_COMMIT_MS
            val shouldSoftCommit = activeDurationMillis >= MAX_COMMIT_MS
            if (shouldFinish || shouldSoftCommit) {
                val fullSegmentQuality = analyzeSegment(activePcm.toByteArray())
                val forceFlush = submittedChunksInSegment > 0 && !fullSegmentQuality.isLikelySpeech
                val chunk = writeChunk(
                    chunkDir = chunkDir,
                    pcmBytes = pendingPcm.toByteArray(),
                    startedAtMillis = pendingStartMillis,
                    endedAtMillis = now,
                    isFinal = true,
                    sequence = ++sequence,
                    sessionId = sessionId,
                    allowControlChunk = true,
                    segmentQuality = fullSegmentQuality,
                    forceControlChunk = forceFlush,
                )
                if (chunk != null) {
                    scope.launch(Dispatchers.IO) { onChunk(chunk) }
                }
                activePcm = ByteArrayOutputStream()
                pendingPcm = ByteArrayOutputStream()
                activeStartMillis = now
                pendingStartMillis = now
                lastPartialMillis = 0L
                silenceFrames = 0
                submittedChunksInSegment = 0
                if (shouldFinish || !fullSegmentQuality.isLikelySpeech) {
                    inSpeech = false
                    preroll.clear()
                    pendingSpeechFrames = 0
                }
            }
        }

        if (inSpeech && activePcm.size() > 0) {
            val now = System.currentTimeMillis()
            val fullSegmentQuality = analyzeSegment(activePcm.toByteArray())
            val forceFlush = submittedChunksInSegment > 0 && !fullSegmentQuality.isLikelySpeech
            val chunk = writeChunk(
                chunkDir = chunkDir,
                pcmBytes = pendingPcm.toByteArray(),
                startedAtMillis = pendingStartMillis,
                endedAtMillis = now,
                isFinal = true,
                sequence = ++sequence,
                sessionId = sessionId,
                allowControlChunk = true,
                segmentQuality = fullSegmentQuality,
                forceControlChunk = forceFlush,
            )
            if (chunk != null) {
                scope.launch(Dispatchers.IO) { onChunk(chunk) }
            }
        }
    }

    private fun writeChunk(
        chunkDir: File,
        pcmBytes: ByteArray,
        startedAtMillis: Long,
        endedAtMillis: Long,
        isFinal: Boolean,
        sequence: Long,
        sessionId: String,
        allowControlChunk: Boolean,
        segmentQuality: SegmentQuality?,
        forceControlChunk: Boolean,
    ): AudioChunk? {
        val stats = audioStats(pcmBytes, pcmBytes.size)
        val chunkQuality = analyzeSegment(pcmBytes)
        val decisionQuality = segmentQuality ?: chunkQuality
        val durationMillis = endedAtMillis - startedAtMillis
        val accepted = if (allowControlChunk) {
            decisionQuality.isLikelySpeech || forceControlChunk
        } else {
            durationMillis >= MIN_SEGMENT_MS && chunkQuality.isLikelySpeech
        }
        if (!accepted) return null
        val file = File(chunkDir, "${UUID.randomUUID()}.wav")
        val outputPcm = if (chunkQuality.isLikelySpeech && decisionQuality.isLikelySpeech) {
            val cleanedPcm = preprocessForAsr(pcmBytes, decisionQuality)
            val cleanedStats = audioStats(cleanedPcm, cleanedPcm.size)
            applyGain(cleanedPcm, cleanedStats, decisionQuality)
        } else {
            pcmBytes
        }
        file.writeBytes(wavBytes(outputPcm))
        return AudioChunk(
            file = file,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            rms = stats.rms,
            peak = stats.peak,
            activeRatio = stats.activeRatio,
            isFinal = isFinal,
            sequence = sequence,
            sessionId = sessionId,
            segmentRms = decisionQuality.rms,
            segmentPeak = decisionQuality.peak,
            segmentActiveRatio = decisionQuality.activeRatio,
            speechMillis = decisionQuality.speechMillis,
            strongSpeechMillis = decisionQuality.strongSpeechMillis,
            speechScore = decisionQuality.score,
            isSpeechLikely = decisionQuality.isLikelySpeech,
            segmentZeroCrossingRate = decisionQuality.zeroCrossingRate,
            segmentPeakToRms = decisionQuality.peakToRms,
            segmentPitchHz = decisionQuality.pitchHz,
            segmentVoicedRatio = decisionQuality.voicedRatio,
            segmentRmsModulation = decisionQuality.rmsModulation,
            segmentImpulseScore = decisionQuality.impulseScore,
            isImpulsiveNoise = decisionQuality.isImpulsiveNoise,
        )
    }

    private data class AudioStats(
        val rms: Double,
        val peak: Int,
        val activeRatio: Double,
        val zeroCrossingRate: Double,
    )

    private fun audioStats(pcm: ByteArray, length: Int): AudioStats {
        return audioStats(pcm = pcm, offset = 0, length = length)
    }

    private fun audioStats(pcm: ByteArray, offset: Int, length: Int): AudioStats {
        if (length < 2) return AudioStats(rms = 0.0, peak = 0, activeRatio = 0.0, zeroCrossingRate = 0.0)
        var sumSquares = 0.0
        var activeSamples = 0
        var peak = 0
        var samples = 0
        var zeroCrossings = 0
        var previousSign = 0
        var index = offset.coerceAtLeast(0)
        val end = (index + length).coerceAtMost(pcm.size)
        while (index + 1 < end) {
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xff)).toShort().toInt()
            val abs = kotlin.math.abs(sample)
            if (abs > ACTIVE_SAMPLE_THRESHOLD) activeSamples++
            if (abs > peak) peak = abs
            val sign = when {
                sample > ZERO_CROSSING_DEADBAND -> 1
                sample < -ZERO_CROSSING_DEADBAND -> -1
                else -> 0
            }
            if (sign != 0) {
                if (previousSign != 0 && previousSign != sign) zeroCrossings++
                previousSign = sign
            }
            sumSquares += (sample * sample).toDouble()
            samples++
            index += 2
        }
        if (samples == 0) return AudioStats(rms = 0.0, peak = 0, activeRatio = 0.0, zeroCrossingRate = 0.0)
        return AudioStats(
            rms = kotlin.math.sqrt(sumSquares / samples),
            peak = peak,
            activeRatio = activeSamples.toDouble() / samples.toDouble(),
            zeroCrossingRate = zeroCrossings.toDouble() / samples.toDouble(),
        )
    }

    private data class SegmentQuality(
        val rms: Double,
        val peak: Int,
        val activeRatio: Double,
        val durationMillis: Long,
        val speechMillis: Long,
        val strongSpeechMillis: Long,
        val peakToRms: Double,
        val zeroCrossingRate: Double,
        val pitchHz: Double,
        val voicedRatio: Double,
        val rmsModulation: Double,
        val impulseScore: Double,
        val isImpulsiveNoise: Boolean,
        val score: Double,
        val isLikelySpeech: Boolean,
    )

    private fun analyzeSegment(pcm: ByteArray): SegmentQuality {
        val stats = audioStats(pcm, pcm.size)
        val sampleCount = pcm.size / 2
        val durationMillis = sampleCount * 1000L / SAMPLE_RATE
        var offset = 0
        var speechMillis = 0L
        var strongSpeechMillis = 0L
        var frameCount = 0
        var zcrSum = 0.0
        var pitchFramesChecked = 0
        var voicedFrames = 0
        val pitchCandidates = mutableListOf<Double>()
        val frameRmsValues = mutableListOf<Double>()
        val strongRuns = mutableListOf<Int>()
        var currentStrongRun = 0
        while (offset < pcm.size) {
            val frameLength = FRAME_BYTES.coerceAtMost(pcm.size - offset)
            val frameStats = audioStats(pcm, offset, frameLength)
            val frameMillis = ((frameLength / 2) * 1000L / SAMPLE_RATE).coerceAtLeast(1L)
            zcrSum += frameStats.zeroCrossingRate
            frameRmsValues += frameStats.rms
            frameCount++
            if (isSegmentSpeechFrame(frameStats)) {
                speechMillis += frameMillis
            }
            if (isStrongSpeechFrame(frameStats)) {
                currentStrongRun++
                strongSpeechMillis += frameMillis
                if (pitchFramesChecked < MAX_PITCH_FRAMES_PER_SEGMENT && frameCount % PITCH_FRAME_STRIDE == 0) {
                    pitchFramesChecked++
                    val pitch = estimatePitchHz(pcm, offset, frameLength)
                    if (pitch > 0.0) {
                        voicedFrames++
                        pitchCandidates += pitch
                    }
                }
            } else if (currentStrongRun > 0) {
                strongRuns += currentStrongRun
                currentStrongRun = 0
            }
            offset += frameLength
        }
        if (currentStrongRun > 0) {
            strongRuns += currentStrongRun
        }

        val peakToRms = if (stats.rms > 0.0) stats.peak / stats.rms else 0.0
        val transientNoise = TransientNoiseGate.analyze(
            frameRmsValues = frameRmsValues,
            strongRuns = strongRuns,
            frameMillis = FRAME_MS.toLong(),
            durationMillis = durationMillis,
            speechMillis = speechMillis,
            strongSpeechMillis = strongSpeechMillis,
            peakToRms = peakToRms,
            voicedRatio = if (pitchFramesChecked == 0) 0.0 else voicedFrames.toDouble() / pitchFramesChecked.toDouble(),
            pitchHz = medianPitch(pitchCandidates),
        )
        val rmsModulation = transientNoise.rmsModulation
        val averageZcr = if (frameCount == 0) stats.zeroCrossingRate else zcrSum / frameCount
        val pitchHz = medianPitch(pitchCandidates)
        val voicedRatio = if (pitchFramesChecked == 0) 0.0 else voicedFrames.toDouble() / pitchFramesChecked.toDouble()
        val hasHumanVoicing = voicedRatio >= MIN_VOICED_RATIO ||
            (pitchHz in MIN_PITCH_HZ..MAX_PITCH_HZ && strongSpeechMillis >= MIN_STRONG_SPEECH_MILLIS * 2) ||
            (strongSpeechMillis >= MIN_STRONG_SPEECH_MILLIS * 4 && peakToRms >= 5.0)
        val steadyNoise = durationMillis >= STEADY_NOISE_MIN_MS &&
            speechMillis >= (durationMillis * STEADY_NOISE_SPEECH_RATIO).toLong() &&
            strongSpeechMillis >= (durationMillis * STEADY_NOISE_STRONG_RATIO).toLong() &&
            (
                rmsModulation <= STEADY_NOISE_MAX_MODULATION ||
                    averageZcr !in SEGMENT_ZCR_MIN..SEGMENT_ZCR_MAX
                ) &&
            peakToRms <= STEADY_NOISE_MAX_PEAK_TO_RMS
        val voiceScore = (speechMillis.toDouble() / MIN_SPEECH_MILLIS).coerceIn(0.0, 1.0)
        val strongScore = (strongSpeechMillis.toDouble() / MIN_STRONG_SPEECH_MILLIS).coerceIn(0.0, 1.0)
        val energyScore = ((stats.rms - RMS_THRESHOLD) / (STRONG_FRAME_RMS_THRESHOLD - RMS_THRESHOLD))
            .coerceIn(0.0, 1.0)
        val dynamicScore = ((peakToRms - MIN_PEAK_TO_RMS) / 4.0).coerceIn(0.0, 1.0)
        val voicedScore = (voicedRatio / 0.45).coerceIn(0.0, 1.0)
        val score = 0.28 * voiceScore +
            0.22 * strongScore +
            0.22 * energyScore +
            0.12 * dynamicScore +
            0.16 * voicedScore
        val likelySpeech = durationMillis >= MIN_SEGMENT_MS &&
            speechMillis >= MIN_SPEECH_MILLIS &&
            strongSpeechMillis >= MIN_STRONG_SPEECH_MILLIS &&
            stats.rms >= SEGMENT_RMS_THRESHOLD &&
            stats.peak >= SEGMENT_PEAK_THRESHOLD &&
            stats.activeRatio >= SEGMENT_ACTIVE_RATIO_THRESHOLD &&
            averageZcr in SEGMENT_ZCR_MIN..SEGMENT_ZCR_MAX &&
            (peakToRms >= MIN_PEAK_TO_RMS || strongSpeechMillis >= MIN_STRONG_SPEECH_MILLIS * 2) &&
            hasHumanVoicing &&
            !steadyNoise &&
            !transientNoise.isLikelyImpact

        return SegmentQuality(
            rms = stats.rms,
            peak = stats.peak,
            activeRatio = stats.activeRatio,
            durationMillis = durationMillis,
            speechMillis = speechMillis,
            strongSpeechMillis = strongSpeechMillis,
            peakToRms = peakToRms,
            zeroCrossingRate = averageZcr,
            pitchHz = pitchHz,
            voicedRatio = voicedRatio,
            rmsModulation = rmsModulation,
            impulseScore = transientNoise.impulseScore,
            isImpulsiveNoise = transientNoise.isLikelyImpact,
            score = score,
            isLikelySpeech = likelySpeech,
        )
    }

    private fun estimatePitchHz(pcm: ByteArray, offset: Int, length: Int): Double {
        val samples = length / 2
        val minLag = (SAMPLE_RATE / MAX_PITCH_HZ).toInt().coerceAtLeast(1)
        val maxLag = (SAMPLE_RATE / MIN_PITCH_HZ).toInt().coerceAtMost(samples - 2)
        if (maxLag <= minLag || samples < maxLag + 2) return 0.0

        var bestLag = 0
        var bestCorrelation = 0.0
        var lag = minLag
        while (lag <= maxLag) {
            var correlation = 0.0
            var energyA = 0.0
            var energyB = 0.0
            var i = 0
            while (i + lag < samples) {
                val a = sampleAt(pcm, offset + i * 2).toDouble()
                val b = sampleAt(pcm, offset + (i + lag) * 2).toDouble()
                correlation += a * b
                energyA += a * a
                energyB += b * b
                i += PITCH_SAMPLE_STEP
            }
            if (energyA > 0.0 && energyB > 0.0) {
                val normalized = correlation / kotlin.math.sqrt(energyA * energyB)
                if (normalized > bestCorrelation) {
                    bestCorrelation = normalized
                    bestLag = lag
                }
            }
            lag++
        }
        if (bestLag == 0 || bestCorrelation < MIN_PITCH_CORRELATION) return 0.0
        return SAMPLE_RATE.toDouble() / bestLag.toDouble()
    }

    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        if (index + 1 >= pcm.size) return 0
        return ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xff)).toShort().toInt()
    }

    private fun medianPitch(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun updateNoiseFloor(
        currentFloor: Double,
        frameRms: Double,
        isCalibrating: Boolean,
        acceptedAsSpeech: Boolean,
    ): Double {
        if (acceptedAsSpeech) return currentFloor
        val boundedRms = if (isCalibrating) {
            frameRms
        } else {
            frameRms.coerceAtMost(currentFloor * NOISE_FLOOR_MAX_STEP_UP)
        }
        val alpha = if (isCalibrating) NOISE_CALIBRATION_ALPHA else NOISE_TRACK_ALPHA
        return (1.0 - alpha) * currentFloor + alpha * boundedRms
    }

    private fun isSpeechFrame(stats: AudioStats, inSpeech: Boolean, noiseFloor: Double): Boolean {
        val floorGate = if (inSpeech) noiseFloor * 1.55 else noiseFloor * 2.35
        val rmsGate = maxOf(RMS_THRESHOLD, floorGate)
        val basicSpeech = stats.rms >= rmsGate &&
            stats.activeRatio >= FRAME_ACTIVE_RATIO_THRESHOLD &&
            stats.peak >= PEAK_THRESHOLD &&
            stats.zeroCrossingRate in FRAME_ZCR_MIN..FRAME_ZCR_MAX
        if (!basicSpeech) return false
        if (inSpeech) return true
        return !TransientNoiseGate.isStartupImpactFrame(
            rms = stats.rms,
            peak = stats.peak,
            activeRatio = stats.activeRatio,
            zeroCrossingRate = stats.zeroCrossingRate,
            noiseFloor = noiseFloor,
        )
    }

    private fun isSegmentSpeechFrame(stats: AudioStats): Boolean =
        stats.rms >= SEGMENT_FRAME_RMS_THRESHOLD &&
            stats.activeRatio >= SEGMENT_FRAME_ACTIVE_RATIO_THRESHOLD &&
            stats.peak >= SEGMENT_FRAME_PEAK_THRESHOLD &&
            stats.zeroCrossingRate in FRAME_ZCR_MIN..FRAME_ZCR_MAX

    private fun isStrongSpeechFrame(stats: AudioStats): Boolean =
        stats.rms >= STRONG_FRAME_RMS_THRESHOLD &&
            stats.activeRatio >= STRONG_FRAME_ACTIVE_RATIO_THRESHOLD &&
            stats.peak >= STRONG_FRAME_PEAK_THRESHOLD &&
            stats.zeroCrossingRate in FRAME_ZCR_MIN..FRAME_ZCR_MAX

    private fun preprocessForAsr(pcm: ByteArray, quality: SegmentQuality): ByteArray {
        if (!quality.isLikelySpeech || pcm.size < FRAME_BYTES) return pcm
        val highPassed = highPassSpeechBand(pcm)
        val firstNoiseRms = estimateSegmentNoiseRms(highPassed, quality)
        val noiseRatio = firstNoiseRms / quality.rms.coerceAtLeast(1.0)
        val spectralReduced = if (shouldUseSpectralDenoise(quality, noiseRatio)) {
            SpectralNoiseReducer.reduceNoise(
                highPassed,
                sampleRate = SAMPLE_RATE,
                strength = SPECTRAL_DENOISE_STRENGTH,
                spectralFloor = SPECTRAL_DENOISE_FLOOR,
            )
        } else {
            highPassed
        }
        val noiseRms = estimateSegmentNoiseRms(spectralReduced, quality)
        return applySoftNoiseGate(spectralReduced, noiseRms, quality)
    }

    private fun shouldUseSpectralDenoise(quality: SegmentQuality, noiseRatio: Double): Boolean =
        quality.durationMillis >= MIN_SPECTRAL_DENOISE_MS &&
            (
                noiseRatio >= SPECTRAL_DENOISE_NOISE_RATIO ||
                    (noiseRatio >= SPECTRAL_DENOISE_BORDERLINE_NOISE_RATIO &&
                        quality.voicedRatio < SPECTRAL_DENOISE_LOW_VOICED_RATIO)
                )

    private fun highPassSpeechBand(pcm: ByteArray): ByteArray {
        if (pcm.size < 4) return pcm
        val out = ByteArray(pcm.size)
        var previousInput = 0.0
        var previousOutput = 0.0
        var index = 0
        while (index + 1 < pcm.size) {
            val input = sampleAt(pcm, index).toDouble()
            val filtered = HIGH_PASS_ALPHA * (previousOutput + input - previousInput)
            val sample = filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[index] = (sample and 0xff).toByte()
            out[index + 1] = ((sample shr 8) and 0xff).toByte()
            previousInput = input
            previousOutput = filtered
            index += 2
        }
        return out
    }

    private fun estimateSegmentNoiseRms(pcm: ByteArray, quality: SegmentQuality): Double {
        val frameRmsValues = mutableListOf<Double>()
        var offset = 0
        while (offset < pcm.size) {
            val frameLength = FRAME_BYTES.coerceAtMost(pcm.size - offset)
            val frameStats = audioStats(pcm, offset, frameLength)
            if (!isStrongSpeechFrame(frameStats)) {
                frameRmsValues += frameStats.rms
            }
            offset += frameLength
        }
        if (frameRmsValues.isEmpty()) return INITIAL_NOISE_FLOOR
        val sorted = frameRmsValues.sorted()
        val index = ((sorted.size - 1) * NOISE_RMS_PERCENTILE).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
            .coerceAtLeast(INITIAL_NOISE_FLOOR)
            .coerceAtMost((quality.rms * MAX_NOISE_TO_SPEECH_RATIO).coerceAtLeast(INITIAL_NOISE_FLOOR))
    }

    private fun applySoftNoiseGate(
        pcm: ByteArray,
        noiseRms: Double,
        quality: SegmentQuality,
    ): ByteArray {
        val gateThreshold = maxOf(
            noiseRms * NOISE_GATE_MULTIPLIER,
            SOFT_GATE_MIN_RMS,
        ).coerceAtMost((quality.rms * SOFT_GATE_MAX_SPEECH_RATIO).coerceAtLeast(SOFT_GATE_MIN_RMS))
        if (gateThreshold <= 0.0) return pcm
        val quietFloor = if (quality.voicedRatio >= CONFIDENT_VOICED_RATIO) {
            STRONG_VOICE_GATE_FLOOR
        } else {
            WEAK_VOICE_GATE_FLOOR
        }
        val out = ByteArray(pcm.size)
        var offset = 0
        while (offset < pcm.size) {
            val frameLength = FRAME_BYTES.coerceAtMost(pcm.size - offset)
            val frameStats = audioStats(pcm, offset, frameLength)
            val frameGain = when {
                isStrongSpeechFrame(frameStats) -> 1.0
                frameStats.rms < gateThreshold -> quietFloor
                frameStats.rms < gateThreshold * TRANSITION_GATE_MULTIPLIER -> TRANSITION_GATE_FLOOR
                else -> 1.0
            }
            var index = offset
            val end = (offset + frameLength).coerceAtMost(pcm.size)
            while (index + 1 < end) {
                val sample = sampleAt(pcm, index)
                val shapedGain = if (frameGain >= 1.0) {
                    1.0
                } else {
                    val ratio = (kotlin.math.abs(sample) / gateThreshold).coerceIn(0.0, 1.0)
                    frameGain + (1.0 - frameGain) * ratio * ratio
                }
                val cleaned = (sample * shapedGain)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[index] = (cleaned and 0xff).toByte()
                out[index + 1] = ((cleaned shr 8) and 0xff).toByte()
                index += 2
            }
            offset += frameLength
        }
        return out
    }

    private fun applyGain(pcm: ByteArray, stats: AudioStats, quality: SegmentQuality): ByteArray {
        if (pcm.isEmpty() || stats.rms <= 0.0) return pcm
        if (quality.score < MIN_GAIN_SPEECH_SCORE) return pcm
        val maxGain = when {
            quality.voicedRatio < MIN_GAIN_VOICED_RATIO -> LOW_VOICE_MAX_GAIN
            quality.score < STRONG_GAIN_SPEECH_SCORE -> MEDIUM_VOICE_MAX_GAIN
            else -> MAX_GAIN
        }
        val gain = (TARGET_RMS / stats.rms).coerceIn(1.0, maxGain)
        if (gain <= 1.05) return pcm
        val out = ByteArray(pcm.size)
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xff)).toShort().toInt()
            val boosted = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[index] = (boosted and 0xff).toByte()
            out[index + 1] = ((boosted shr 8) and 0xff).toByte()
            index += 2
        }
        return out
    }

    private fun wavBytes(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val totalSize = dataSize + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
        return header + pcm
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val FRAME_MS = 30
        private const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * CHANNELS * BITS_PER_SAMPLE / 8
        private const val PREROLL_FRAMES = 10
        private const val START_SPEECH_FRAMES = 3
        private const val NOISE_CALIBRATION_MS = 700L
        private const val SILENCE_COMMIT_MS = 650
        private const val PARTIAL_INTERVAL_MS = 650
        private const val MIN_PARTIAL_MS = 620
        private const val MIN_SEGMENT_MS = 420
        private const val MIN_PARTIAL_BYTES = SAMPLE_RATE * MIN_PARTIAL_MS / 1000 * CHANNELS * BITS_PER_SAMPLE / 8
        private const val MAX_COMMIT_MS = 8_000
        private const val INITIAL_NOISE_FLOOR = 24.0
        private const val RMS_THRESHOLD = 52.0
        private const val ACTIVE_SAMPLE_THRESHOLD = 120
        private const val ZERO_CROSSING_DEADBAND = 24
        private const val PEAK_THRESHOLD = 220
        private const val FRAME_ACTIVE_RATIO_THRESHOLD = 0.006
        private const val FRAME_ZCR_MIN = 0.008
        private const val FRAME_ZCR_MAX = 0.26
        private const val SEGMENT_RMS_THRESHOLD = 40.0
        private const val SEGMENT_PEAK_THRESHOLD = 230
        private const val SEGMENT_ACTIVE_RATIO_THRESHOLD = 0.0035
        private const val SEGMENT_ZCR_MIN = 0.006
        private const val SEGMENT_ZCR_MAX = 0.24
        private const val SEGMENT_FRAME_RMS_THRESHOLD = 52.0
        private const val SEGMENT_FRAME_PEAK_THRESHOLD = 220
        private const val SEGMENT_FRAME_ACTIVE_RATIO_THRESHOLD = 0.006
        private const val STRONG_FRAME_RMS_THRESHOLD = 85.0
        private const val STRONG_FRAME_PEAK_THRESHOLD = 350
        private const val STRONG_FRAME_ACTIVE_RATIO_THRESHOLD = 0.010
        private const val MIN_SPEECH_MILLIS = 150L
        private const val MIN_STRONG_SPEECH_MILLIS = 60L
        private const val MIN_PEAK_TO_RMS = 2.4
        private const val MIN_GAIN_SPEECH_SCORE = 0.50
        private const val STRONG_GAIN_SPEECH_SCORE = 0.72
        private const val MIN_GAIN_VOICED_RATIO = 0.16
        private const val TARGET_RMS = 1600.0
        private const val MAX_GAIN = 8.0
        private const val MEDIUM_VOICE_MAX_GAIN = 5.2
        private const val LOW_VOICE_MAX_GAIN = 2.8
        private const val NOISE_CALIBRATION_ALPHA = 0.35
        private const val NOISE_TRACK_ALPHA = 0.12
        private const val NOISE_FLOOR_MAX_STEP_UP = 2.5
        private const val STEADY_NOISE_MIN_MS = 1_200L
        private const val STEADY_NOISE_SPEECH_RATIO = 0.78
        private const val STEADY_NOISE_STRONG_RATIO = 0.55
        private const val STEADY_NOISE_MAX_MODULATION = 0.24
        private const val STEADY_NOISE_MAX_PEAK_TO_RMS = 5.8
        private const val MIN_PITCH_HZ = 75.0
        private const val MAX_PITCH_HZ = 360.0
        private const val MIN_PITCH_CORRELATION = 0.42
        private const val MIN_VOICED_RATIO = 0.10
        private const val CONFIDENT_VOICED_RATIO = 0.28
        private const val MAX_PITCH_FRAMES_PER_SEGMENT = 36
        private const val PITCH_FRAME_STRIDE = 2
        private const val PITCH_SAMPLE_STEP = 2
        private const val HIGH_PASS_ALPHA = 0.96
        private const val MIN_SPECTRAL_DENOISE_MS = 620L
        private const val SPECTRAL_DENOISE_STRENGTH = 0.82
        private const val SPECTRAL_DENOISE_FLOOR = 0.12
        private const val SPECTRAL_DENOISE_NOISE_RATIO = 0.30
        private const val SPECTRAL_DENOISE_BORDERLINE_NOISE_RATIO = 0.22
        private const val SPECTRAL_DENOISE_LOW_VOICED_RATIO = 0.18
        private const val NOISE_RMS_PERCENTILE = 0.25
        private const val MAX_NOISE_TO_SPEECH_RATIO = 0.42
        private const val NOISE_GATE_MULTIPLIER = 2.15
        private const val SOFT_GATE_MIN_RMS = 38.0
        private const val SOFT_GATE_MAX_SPEECH_RATIO = 0.58
        private const val STRONG_VOICE_GATE_FLOOR = 0.32
        private const val WEAK_VOICE_GATE_FLOOR = 0.55
        private const val TRANSITION_GATE_MULTIPLIER = 1.45
        private const val TRANSITION_GATE_FLOOR = 0.72
    }
}
