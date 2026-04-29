package com.linglin.lingjiang.network

import com.linglin.lingjiang.BuildConfig

data class ApiConfig(
    val asrBaseUrl: String,
    val asrApiKey: String,
    val asrModel: String,
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val cloudLlmBaseUrl: String,
    val cloudLlmApiKey: String,
    val cloudLlmModel: String,
) {
    val usesSystemSpeech: Boolean = asrModel.equals("system-speech", ignoreCase = true) ||
        asrModel.equals("android-speech", ignoreCase = true) ||
        asrModel.equals("xiaomi-speech", ignoreCase = true)
    val usesOnDeviceSherpa: Boolean = asrModel.equals("on-device-sherpa", ignoreCase = true) ||
        asrModel.equals("sherpa-onnx", ignoreCase = true) ||
        asrModel.equals("local-sherpa", ignoreCase = true)
    val usesOnDeviceParaformer: Boolean = asrModel.equals("on-device-paraformer", ignoreCase = true) ||
        asrModel.equals("on-device-sherpa-paraformer", ignoreCase = true) ||
        asrModel.equals("sherpa-onnx-paraformer", ignoreCase = true) ||
        asrModel.equals("offline-paraformer", ignoreCase = true)
    val usesOnDeviceAsr: Boolean = usesOnDeviceSherpa || usesOnDeviceParaformer
    val usesBridgeAsr: Boolean = asrModel.startsWith("pc-", ignoreCase = true) ||
        asrModel.equals("remote-funasr", ignoreCase = true) ||
        asrModel.equals("funasr-bridge", ignoreCase = true) ||
        asrModel.equals("lingjiang-funasr", ignoreCase = true)
    val isLocalAsrEndpoint: Boolean = asrBaseUrl.contains("127.0.0.1") ||
        asrBaseUrl.contains("localhost", ignoreCase = true) ||
        asrBaseUrl.contains("10.0.2.2")
    val isLocalLlmEndpoint: Boolean = llmBaseUrl.contains("127.0.0.1") ||
        llmBaseUrl.contains("localhost", ignoreCase = true) ||
        llmBaseUrl.contains("10.0.2.2")
    val usesMnnChatLlm: Boolean = isLocalLlmEndpoint &&
        (llmBaseUrl.contains("8080") || llmModel.contains("MNN", ignoreCase = true))
    val hasAsr: Boolean = usesSystemSpeech || usesOnDeviceAsr || (
        asrBaseUrl.isNotBlank() &&
            asrModel.isNotBlank() &&
            (asrApiKey.isNotBlank() || isLocalAsrEndpoint)
        )
    val isPortableAsr: Boolean = hasAsr && (usesOnDeviceAsr || usesSystemSpeech || !isLocalAsrEndpoint)
    val asrModeLabel: String = when {
        !hasAsr -> "未配置"
        usesOnDeviceParaformer -> "本机离线 Paraformer"
        usesOnDeviceSherpa -> "本机离线 Sherpa"
        usesSystemSpeech -> "系统识别"
        isLocalAsrEndpoint -> "PC 开发桥"
        usesBridgeAsr -> "远端 FunASR"
        else -> "云端转写"
    }
    val hasLlm: Boolean = llmBaseUrl.isNotBlank() &&
        llmModel.isNotBlank() &&
        (llmApiKey.isNotBlank() || isLocalLlmEndpoint)
    val hasCloudLlm: Boolean = cloudLlmBaseUrl.isNotBlank() &&
        cloudLlmModel.isNotBlank() &&
        cloudLlmApiKey.isNotBlank()
    val llmModeLabel: String = when {
        hasCloudLlm && hasLlm -> "本机+云端"
        hasCloudLlm -> "云端 LLM"
        !hasLlm -> "未配置"
        usesMnnChatLlm -> "本机 MNNChat"
        isLocalLlmEndpoint -> "本机 LLM"
        else -> "云端 LLM"
    }

    companion object {
        fun fromBuildConfig(): ApiConfig = ApiConfig(
            asrBaseUrl = BuildConfig.ASR_BASE_URL,
            asrApiKey = BuildConfig.ASR_API_KEY,
            asrModel = BuildConfig.ASR_MODEL,
            llmBaseUrl = BuildConfig.LLM_BASE_URL,
            llmApiKey = BuildConfig.LLM_API_KEY,
            llmModel = BuildConfig.LLM_MODEL,
            cloudLlmBaseUrl = BuildConfig.CLOUD_LLM_BASE_URL,
            cloudLlmApiKey = BuildConfig.CLOUD_LLM_API_KEY,
            cloudLlmModel = BuildConfig.CLOUD_LLM_MODEL,
        )
    }
}
