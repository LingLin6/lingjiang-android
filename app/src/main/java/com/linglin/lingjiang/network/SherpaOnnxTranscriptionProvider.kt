package com.linglin.lingjiang.network

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.linglin.lingjiang.audio.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SherpaOnnxTranscriptionProvider(
    private val context: Context,
) : CloudTranscriptionProvider {
    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    private val streams = mutableMapOf<String, OnlineStream>()
    private val lastTexts = mutableMapOf<String, String>()

    override suspend fun transcribe(chunk: AudioChunk): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val samples = readPcm16WavAsFloat(chunk.file.readBytes())
            if (samples.isEmpty()) return@runCatching TranscriptionResult(text = "")

            synchronized(lock) {
                val localRecognizer = recognizer ?: createRecognizer().also { recognizer = it }
                val stream = streams.getOrPut(chunk.sessionId) { localRecognizer.createStream() }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                if (chunk.isFinal) {
                    stream.inputFinished()
                }
                while (localRecognizer.isReady(stream)) {
                    localRecognizer.decode(stream)
                }
                val text = cleanText(localRecognizer.getResult(stream).text)
                if (text.isNotBlank()) {
                    lastTexts[chunk.sessionId] = text
                }
                val emittedText = if (chunk.isFinal && text.isBlank()) {
                    lastTexts[chunk.sessionId].orEmpty()
                } else {
                    text
                }
                if (chunk.isFinal) {
                    streams.remove(chunk.sessionId)?.release()
                    lastTexts.remove(chunk.sessionId)
                }
                if (emittedText.isNotBlank()) {
                    Log.i(
                        TAG,
                        "sherpa sequence=${chunk.sequence} final=${chunk.isFinal} samples=${samples.size} text=$emittedText",
                    )
                }
                TranscriptionResult(text = emittedText)
            }
        }
    }

    private fun createRecognizer(): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(context.assets, config)
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
        text.replace(" ", "").trim()

    companion object {
        private const val TAG = "LingJiangSherpa"
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile"
    }
}
