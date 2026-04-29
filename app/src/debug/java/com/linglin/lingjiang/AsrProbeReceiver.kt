package com.linglin.lingjiang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.linglin.lingjiang.audio.AudioChunk
import com.linglin.lingjiang.network.SherpaOnnxOfflineParaformerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AsrProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val file = probeFile(context, intent)
                check(file.exists()) {
                    "Probe audio not found: ${file.absolutePath}. Push a 16k mono PCM WAV there before broadcasting."
                }
                val provider = SherpaOnnxOfflineParaformerProvider(context.applicationContext)
                val now = System.currentTimeMillis()
                val started = System.currentTimeMillis()
                val result = provider.transcribe(
                    AudioChunk(
                        file = file,
                        startedAtMillis = now,
                        endedAtMillis = now + 5_600,
                        rms = 2_891.0,
                        peak = 14_277,
                        activeRatio = 0.1,
                        isFinal = true,
                        sequence = 1,
                        sessionId = "debug-probe",
                    ),
                ).getOrThrow()
                Log.i(TAG, "probe latencyMs=${System.currentTimeMillis() - started} text=${result.text}")
            }.onFailure {
                Log.e(TAG, "probe failed", it)
            }
            pending.finish()
        }
    }

    companion object {
        const val ACTION = "com.linglin.lingjiang.DEBUG_ASR_PROBE"
        private const val TAG = "LingJiangAsrProbe"
    }
}

private fun probeFile(context: Context, intent: Intent): File {
    val explicitPath = intent.getStringExtra("path")
    if (!explicitPath.isNullOrBlank()) return File(explicitPath)
    return File(context.getExternalFilesDir(null), "lingjiang_probe.wav")
}
