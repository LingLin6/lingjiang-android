package com.linglin.lingjiang.network

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.linglin.lingjiang.audio.AudioChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SherpaOnnxOfflineParaformerProvider(
    private val context: Context,
) : CloudTranscriptionProvider {
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private val buffers = mutableMapOf<String, SegmentBuffer>()
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        warmupScope.launch {
            runCatching {
                synchronized(lock) {
                    if (recognizer == null) {
                        recognizer = createRecognizer()
                    }
                }
            }.onSuccess {
                Log.i(TAG, "paraformer warmup ready")
            }.onFailure {
                Log.w(TAG, "paraformer warmup failed", it)
            }
        }
    }

    override suspend fun transcribe(chunk: AudioChunk): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!chunk.isSpeechLikely) {
                synchronized(lock) {
                    if (chunk.isFinal) {
                        buffers.remove(chunk.sessionId)
                    }
                }
                Log.i(
                    TAG,
                    "drop weak-audio sequence=${chunk.sequence} final=${chunk.isFinal} " +
                        "score=${"%.2f".format(chunk.speechScore)} speechMs=${chunk.speechMillis} " +
                        "strongMs=${chunk.strongSpeechMillis} rms=${"%.0f".format(chunk.segmentRms)} " +
                        "peak=${chunk.segmentPeak} impulse=${"%.2f".format(chunk.segmentImpulseScore)} " +
                        "impact=${chunk.isImpulsiveNoise}",
                )
                return@runCatching TranscriptionResult(text = "")
            }

            val samples = readPcm16WavAsFloat(chunk.file.readBytes())
            if (samples.isEmpty()) return@runCatching TranscriptionResult(text = "")

            synchronized(lock) {
                val buffer = buffers.getOrPut(chunk.sessionId) { SegmentBuffer() }
                buffer.append(
                    samples = samples,
                    startedAtMillis = chunk.startedAtMillis,
                    endedAtMillis = chunk.endedAtMillis,
                )

                val bufferedMs = buffer.durationMillis
                val shouldDecodePreview = !chunk.isFinal &&
                    bufferedMs >= PREVIEW_DECODE_MS &&
                    buffer.endedAtMillis - buffer.lastPreviewDecodedAtMillis >= PREVIEW_INTERVAL_MS
                val shouldDecodeFinal = chunk.isFinal && bufferedMs >= MIN_FINAL_DECODE_MS
                if (!shouldDecodePreview && !shouldDecodeFinal) {
                    if (chunk.isFinal) {
                        buffers.remove(chunk.sessionId)
                    }
                    return@runCatching TranscriptionResult(text = "")
                }

                val localRecognizer = recognizer ?: createRecognizer().also { recognizer = it }
                val rawText = runCatching {
                    decode(localRecognizer, buffer.samples())
                }.getOrElse { throwable ->
                    Log.w(
                        TAG,
                        "paraformer decode dropped boundary segment sequence=${chunk.sequence} " +
                            "final=${chunk.isFinal} bufferedMs=$bufferedMs",
                        throwable,
                    )
                    ""
                }
                val text = if (shouldDropDecodedText(rawText, chunk, bufferedMs)) "" else rawText
                Log.i(
                    TAG,
                    "paraformer sequence=${chunk.sequence} final=${chunk.isFinal} bufferedMs=$bufferedMs " +
                        "score=${"%.2f".format(chunk.speechScore)} speechMs=${chunk.speechMillis} " +
                        "strongMs=${chunk.strongSpeechMillis} impulse=${"%.2f".format(chunk.segmentImpulseScore)} " +
                        "impact=${chunk.isImpulsiveNoise} raw=$rawText text=$text",
                )

                val startedAt = buffer.startedAtMillis
                val endedAt = buffer.endedAtMillis
                if (chunk.isFinal) {
                    buffers.remove(chunk.sessionId)
                } else {
                    buffer.lastPreviewDecodedAtMillis = buffer.endedAtMillis
                }

                TranscriptionResult(
                    text = text,
                    confidence = chunk.speechScore.toFloat().coerceIn(0f, 1f),
                    startedAtMillis = startedAt,
                    endedAtMillis = endedAt,
                )
            }
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "$MODEL_DIR/model.int8.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = RECOGNIZER_THREADS,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(context.assets, config)
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return cleanText(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    private fun readPcm16WavAsFloat(bytes: ByteArray): FloatArray {
        val dataOffset = findWavDataOffset(bytes)
        if (dataOffset < 0 || dataOffset >= bytes.size) return FloatArray(0)
        val sampleCount = (bytes.size - dataOffset) / 2
        val samples = FloatArray(sampleCount)
        var byteIndex = dataOffset
        var sampleIndex = 0
        while (sampleIndex < sampleCount && byteIndex + 1 < bytes.size) {
            val sample = ((bytes[byteIndex + 1].toInt() shl 8) or (bytes[byteIndex].toInt() and 0xff)).toShort()
            samples[sampleIndex] = sample.toFloat() / Short.MAX_VALUE.toFloat()
            byteIndex += 2
            sampleIndex++
        }
        return samples
    }

    private fun findWavDataOffset(bytes: ByteArray): Int {
        if (bytes.size < 44) return -1
        var index = 12
        while (index + 8 <= bytes.size) {
            val chunkId = String(bytes, index, 4, Charsets.US_ASCII)
            val chunkSize = (bytes[index + 4].toInt() and 0xff) or
                ((bytes[index + 5].toInt() and 0xff) shl 8) or
                ((bytes[index + 6].toInt() and 0xff) shl 16) or
                ((bytes[index + 7].toInt() and 0xff) shl 24)
            val dataStart = index + 8
            if (chunkId == "data") return dataStart
            index = dataStart + chunkSize.coerceAtLeast(0)
        }
        return 44
    }

    private fun cleanText(text: String): String =
        TranscriptNormalizer.normalize(text)

    private fun shouldDropDecodedText(text: String, chunk: AudioChunk, bufferedMs: Long): Boolean {
        val normalized = text
            .replace(" ", "")
            .replace("，", "")
            .replace("。", "")
            .replace("？", "")
            .replace("！", "")
            .trim()
        if (normalized.isBlank()) return true
        if (!chunk.isSpeechLikely) return true
        if (isIsolatedLowValueText(normalized)) return true
        if (isLowQualityHallucination(normalized)) return true

        val weakAudio = chunk.speechScore < WEAK_AUDIO_SCORE ||
            chunk.strongSpeechMillis < MIN_CONFIDENT_STRONG_SPEECH_MS ||
            chunk.segmentRms < MIN_CONFIDENT_RMS
        if (!weakAudio) return false

        val isShort = normalized.length <= SHORT_HALLUCINATION_CHARS
        val isFiller = normalized in FILLER_HALLUCINATIONS ||
            normalized.toSet().size <= 1 ||
            FILLER_HALLUCINATIONS.any { normalized == it.repeat(2) }
        val tooLittleTextForLongAudio = bufferedMs >= LONG_AUDIO_MS && normalized.length <= 1
        return (isShort && isFiller) || tooLittleTextForLongAudio
    }

    private fun isIsolatedLowValueText(text: String): Boolean {
        if (text.length <= 1 && text.all { it in ISOLATED_LOW_VALUE_CHARS }) return true
        if (text in ISOLATED_HALLUCINATIONS) return true
        if (text.length <= 4) {
            val lowValueRatio = text.count { it in ISOLATED_LOW_VALUE_CHARS }.toDouble() / text.length
            val unique = text.toSet().size
            if (lowValueRatio >= 0.75 && unique <= 2) return true
        }
        if (text.length <= 4 && text.toSet().size == 1 && text.first() in REPEATABLE_LOW_VALUE_CHARS) return true
        return false
    }

    private fun isLowQualityHallucination(text: String): Boolean {
        if (text.length < MIN_TEXT_QUALITY_CHARS) return false
        val chars = text.toList()
        val mostCommonRatio = chars
            .groupingBy { it }
            .eachCount()
            .values
            .maxOrNull()
            ?.toDouble()
            ?.div(chars.size)
            ?: 0.0
        val lowValueRatio = chars.count { it in LOW_VALUE_CHARS }.toDouble() / chars.size
        val uniqueRatio = chars.toSet().size.toDouble() / chars.size
        val hasRepeatedJunk = REPEATED_JUNK_FRAGMENTS.any { text.contains(it) }
        return mostCommonRatio >= 0.34 ||
            lowValueRatio >= 0.62 ||
            (text.length >= 14 && uniqueRatio <= 0.42) ||
            hasRepeatedJunk
    }

    private class SegmentBuffer {
        private val pcm = ByteArrayOutputStream()
        var startedAtMillis: Long = 0L
            private set
        var endedAtMillis: Long = 0L
            private set
        var lastPreviewDecodedAtMillis: Long = 0L

        val durationMillis: Long
            get() = if (startedAtMillis == 0L) 0L else endedAtMillis - startedAtMillis

        fun append(samples: FloatArray, startedAtMillis: Long, endedAtMillis: Long) {
            if (this.startedAtMillis == 0L) {
                this.startedAtMillis = startedAtMillis
            }
            this.endedAtMillis = endedAtMillis
            var index = 0
            while (index < samples.size) {
                val sample = (samples[index] * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm.write(sample and 0xff)
                pcm.write((sample shr 8) and 0xff)
                index++
            }
        }

        fun samples(): FloatArray {
            val bytes = pcm.toByteArray()
            val samples = FloatArray(bytes.size / 2)
            var byteIndex = 0
            var sampleIndex = 0
            while (sampleIndex < samples.size && byteIndex + 1 < bytes.size) {
                val sample = ((bytes[byteIndex + 1].toInt() shl 8) or (bytes[byteIndex].toInt() and 0xff)).toShort()
                samples[sampleIndex] = sample.toFloat() / Short.MAX_VALUE.toFloat()
                byteIndex += 2
                sampleIndex++
            }
            return samples
        }
    }

    companion object {
        private const val TAG = "LingJiangParaformer"
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "sherpa-onnx-paraformer-zh-small-2024-03-09"
        private const val RECOGNIZER_THREADS = 4
        private const val PREVIEW_DECODE_MS = 1_150L
        private const val PREVIEW_INTERVAL_MS = 900L
        private const val MIN_FINAL_DECODE_MS = 800L
        private const val WEAK_AUDIO_SCORE = 0.52
        private const val MIN_CONFIDENT_STRONG_SPEECH_MS = 45L
        private const val MIN_CONFIDENT_RMS = 36.0
        private const val SHORT_HALLUCINATION_CHARS = 4
        private const val LONG_AUDIO_MS = 2_000L
        private const val MIN_TEXT_QUALITY_CHARS = 8
        private val ISOLATED_LOW_VALUE_CHARS = setOf(
            '嗯',
            '啊',
            '呃',
            '哦',
            '诶',
            '哎',
            '唉',
            '呀',
            '对',
            '好',
            '是',
        )
        private val REPEATABLE_LOW_VALUE_CHARS = setOf(
            '嗯',
            '啊',
            '哦',
            '哎',
            '对',
            '好',
        )
        private val ISOLATED_HALLUCINATIONS = setOf(
            "嗯",
            "啊",
            "呃",
            "哦",
            "诶",
            "哎",
            "唉",
            "哎呀",
            "啊去",
            "好的",
            "对",
            "是",
            "对对",
            "对对对",
            "好好",
        )
        private val LOW_VALUE_CHARS = setOf(
            '嗯',
            '啊',
            '呃',
            '哦',
            '诶',
            '哎',
            '唉',
            '的',
            '了',
            '好',
            '谢',
        )
        private val REPEATED_JUNK_FRAGMENTS = listOf(
            "好的好的",
            "谢谢谢",
            "的的的",
            "我我我",
            "法法法",
        )
        private val FILLER_HALLUCINATIONS = setOf(
            "嗯",
            "啊",
            "呃",
            "哦",
            "诶",
            "好",
            "好的",
            "谢谢",
            "拜拜",
            "对",
            "是",
        )
    }
}
