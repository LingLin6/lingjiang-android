package com.linglin.lingjiang.audio

import kotlinx.coroutines.flow.Flow

data class CaptionUpdate(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float?,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isDiagnostic: Boolean = false,
)

interface RealtimeCaptionProvider {
    val updates: Flow<CaptionUpdate>
    fun start()
    fun stop()
}
