package com.linglin.lingjiang.network

import com.linglin.lingjiang.audio.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class OpenAiTranscriptionProvider(
    private val client: OkHttpClient,
    private val config: ApiConfig,
) : CloudTranscriptionProvider {
    override suspend fun transcribe(chunk: AudioChunk): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        if (!config.hasAsr) {
            return@withContext Result.failure(IllegalStateException("ASR 接口未配置"))
        }
        if (config.usesBridgeAsr) {
            return@withContext transcribeViaPcBridge(chunk)
        }
        if (config.asrModel.contains("audio", ignoreCase = true)) {
            return@withContext transcribeViaChatAudio(chunk)
        }
        runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", config.asrModel)
                .addFormDataPart(
                    "file",
                    chunk.file.name,
                    chunk.file.asRequestBody("audio/wav".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url(endpoint(config.asrBaseUrl, "audio/transcriptions"))
                .header("Authorization", "Bearer ${config.asrApiKey}")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("ASR 请求失败 ${response.code}: $raw")
                val json = JSONObject(raw)
                TranscriptionResult(text = json.optString("text").trim())
            }
        }
    }

    private fun transcribeViaPcBridge(chunk: AudioChunk): Result<TranscriptionResult> = runCatching {
        val audioBase64 = Base64.getEncoder().encodeToString(chunk.file.readBytes())
        val payload = JSONObject()
            .put("session_id", chunk.sessionId)
            .put("sequence", chunk.sequence)
            .put("is_final", chunk.isFinal)
            .put("audio_base64", audioBase64)
        val request = Request.Builder()
            .url(endpoint(config.asrBaseUrl, "asr"))
            .header("Content-Type", "application/json")
            .apply {
                if (config.asrApiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.asrApiKey}")
                }
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("PC ASR 桥请求失败 ${response.code}: $raw")
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) {
                error(json.optString("error", "PC ASR 桥转写失败"))
            }
            TranscriptionResult(
                text = json.optString("text").trim(),
                confidence = null,
            )
        }
    }.recoverCatching { throwable ->
        if (throwable is IOException && config.isLocalAsrEndpoint) {
            error("PC ASR 桥未连接：请确认电脑端 FunASR 桥已启动，并已执行 adb reverse tcp:8765 tcp:8765")
        }
        if (throwable is IOException) {
            error("远端 ASR 服务未连接：请确认手机网络可用，ASR 服务地址可访问")
        }
        throw throwable
    }

    private fun transcribeViaChatAudio(chunk: AudioChunk): Result<TranscriptionResult> = runCatching {
        val audioBase64 = Base64.getEncoder().encodeToString(chunk.file.readBytes())
        val payload = JSONObject()
            .put("model", config.asrModel)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put(
                                            "text",
                                            "请把这段中文音频转写成原文。只输出转写文本，不要解释；如果没有可识别语音，输出空字符串。",
                                        ),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject()
                                                .put("data", audioBase64)
                                                .put("format", "wav"),
                                        ),
                                ),
                        ),
                ),
            )
        val request = Request.Builder()
            .url(endpoint(config.asrBaseUrl, "chat/completions"))
            .header("Authorization", "Bearer ${config.asrApiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("音频模型转写失败 ${response.code}: $raw")
            val content = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
            TranscriptionResult(text = cleanTranscript(content))
        }
    }

    private fun cleanTranscript(content: String): String {
        val trimmed = content.trim().trim('"', '“', '”')
        if (trimmed.contains("上传音频", ignoreCase = true) ||
            trimmed.contains("upload", ignoreCase = true) ||
            trimmed.contains("audio file", ignoreCase = true)
        ) {
            error("音频模型没有接收到音频内容：$trimmed")
        }
        val silenceMarkers = listOf(
            "",
            "空字符串",
            "无",
            "无可识别语音",
            "没有可识别语音",
            "无法识别",
            "静音",
        )
        return if (silenceMarkers.any { trimmed.equals(it, ignoreCase = true) }) {
            ""
        } else {
            TranscriptNormalizer.normalize(trimmed)
        }
    }
}
