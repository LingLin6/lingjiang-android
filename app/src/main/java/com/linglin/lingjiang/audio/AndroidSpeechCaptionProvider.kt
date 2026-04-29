package com.linglin.lingjiang.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Locale

class AndroidSpeechCaptionProvider(private val context: Context) : RealtimeCaptionProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _updates = MutableSharedFlow<CaptionUpdate>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val updates: Flow<CaptionUpdate> = _updates

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var restartPending = false
    private var lastRmsLogAt = 0L
    private var lastBufferLogAt = 0L

    override fun start() {
        if (running) return
        running = true
        mainHandler.post {
            val available = SpeechRecognizer.isRecognitionAvailable(context)
            val service = defaultRecognitionService()
            Log.i(TAG, "start available=$available service=$service")
            if (!available) {
                _updates.tryEmit(
                    CaptionUpdate(
                        text = "系统语音识别不可用，云端转写仍会继续尝试。",
                        isFinal = false,
                        confidence = null,
                        isDiagnostic = true,
                    ),
                )
                return@post
            }
            recognizer = if (service != null) {
                SpeechRecognizer.createSpeechRecognizer(context, service)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }.also { speechRecognizer ->
                speechRecognizer.setRecognitionListener(listener)
                startListeningSafely(speechRecognizer)
            }
        }
    }

    override fun stop() {
        running = false
        restartPending = false
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            Log.i(TAG, "stop")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "onReadyForSpeech keys=${params?.keySet()?.joinToString()}")
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = System.currentTimeMillis()
            if (now - lastRmsLogAt >= RMS_LOG_INTERVAL_MS) {
                lastRmsLogAt = now
                Log.d(TAG, "onRmsChanged rms=$rmsdB")
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            val now = System.currentTimeMillis()
            if (now - lastBufferLogAt >= BUFFER_LOG_INTERVAL_MS) {
                lastBufferLogAt = now
                Log.d(TAG, "onBufferReceived bytes=${buffer?.size ?: 0}")
            }
        }

        override fun onEndOfSpeech() {
            Log.i(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            if (!running) return
            Log.w(TAG, "onError error=${errorName(error)}")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    scheduleRestart(700L)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    scheduleRestart(1_500L)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _updates.tryEmit(
                        CaptionUpdate(
                            text = "麦克风权限不可用，请重新授权后再开始。",
                            isFinal = false,
                            confidence = null,
                            isDiagnostic = true,
                        ),
                    )
                }
                else -> {
                    scheduleRestart(1_200L)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            Log.i(TAG, "onResults ${debugResults(results)}")
            emitBest(results, final = true)
            scheduleRestart(700L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            Log.i(TAG, "onPartialResults ${debugResults(partialResults)}")
            emitBest(partialResults, final = false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "onEvent type=$eventType keys=${params?.keySet()?.joinToString()}")
        }
    }

    private fun emitBest(bundle: Bundle?, final: Boolean) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim().orEmpty()
        if (text.isBlank()) return
        val confidence = bundle
            ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?.firstOrNull()
            ?.takeIf { it >= 0f }
        _updates.tryEmit(CaptionUpdate(text = text, isFinal = final, confidence = confidence))
    }

    private fun scheduleRestart(delayMillis: Long) {
        if (!running || restartPending) return
        restartPending = true
        Log.i(TAG, "scheduleRestart delayMillis=$delayMillis")
        mainHandler.postDelayed({
            restartPending = false
            if (running) {
                recognizer?.cancel()
                recognizer?.let { startListeningSafely(it) }
            }
        }, delayMillis)
    }

    private fun startListeningSafely(speechRecognizer: SpeechRecognizer) {
        runCatching {
            Log.i(TAG, "startListening intent=${intent().extras?.keySet()?.joinToString()}")
            speechRecognizer.startListening(intent())
        }.onFailure {
            Log.w(TAG, "startListening failed", it)
            scheduleRestart(1_500L)
        }
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.CHINA.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
    }

    private fun defaultRecognitionService() = Settings.Secure
        .getString(context.contentResolver, "voice_recognition_service")
        ?.takeIf { it.isNotBlank() }
        ?.let(android.content.ComponentName::unflattenFromString)

    private fun debugResults(bundle: Bundle?): String {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val scores = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.joinToString()
        return "matches=$matches scores=$scores keys=${bundle?.keySet()?.joinToString()}"
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "UNKNOWN_$error"
    }

    private companion object {
        private const val TAG = "LingJiangSpeech"
        private const val RMS_LOG_INTERVAL_MS = 500L
        private const val BUFFER_LOG_INTERVAL_MS = 1_000L
    }
}
