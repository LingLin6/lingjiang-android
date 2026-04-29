package com.linglin.lingjiang

import android.app.Application
import com.linglin.lingjiang.audio.AndroidSpeechCaptionProvider
import com.linglin.lingjiang.audio.AudioSegmentRecorder
import com.linglin.lingjiang.data.LingJiangDatabase
import com.linglin.lingjiang.data.SessionRepository
import com.linglin.lingjiang.network.ApiConfig
import com.linglin.lingjiang.network.OpenAiChatAnalysisProvider
import com.linglin.lingjiang.network.OpenAiTranscriptionProvider
import com.linglin.lingjiang.network.SherpaOnnxOfflineParaformerProvider
import com.linglin.lingjiang.network.SherpaOnnxTranscriptionProvider
import com.linglin.lingjiang.session.SessionCoordinator
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LingJiangApplication : Application() {
    lateinit var coordinator: SessionCoordinator
        private set

    lateinit var repository: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()

        val database = LingJiangDatabase.create(this)
        repository = SessionRepository(database.sessionDao())

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()

        val apiConfig = ApiConfig.fromBuildConfig()
        val transcriptionProvider = when {
            apiConfig.usesOnDeviceParaformer -> SherpaOnnxOfflineParaformerProvider(this)
            apiConfig.usesOnDeviceSherpa -> SherpaOnnxTranscriptionProvider(this)
            else -> OpenAiTranscriptionProvider(httpClient, apiConfig)
        }
        coordinator = SessionCoordinator(
            appContext = this,
            repository = repository,
            captionProvider = AndroidSpeechCaptionProvider(this),
            recorder = AudioSegmentRecorder(this),
            transcriptionProvider = transcriptionProvider,
            analysisProvider = OpenAiChatAnalysisProvider(httpClient, apiConfig),
        )
    }
}
