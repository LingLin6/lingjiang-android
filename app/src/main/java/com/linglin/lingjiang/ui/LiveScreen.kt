package com.linglin.lingjiang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linglin.lingjiang.data.ResearchTaskEntity
import com.linglin.lingjiang.model.SessionStatus
import com.linglin.lingjiang.session.LiveInsight
import com.linglin.lingjiang.session.SessionUiState

@Composable
fun LiveScreen(
    state: SessionUiState,
    researchTasks: List<ResearchTaskEntity>,
    hasAudioPermission: Boolean,
    requestPermissions: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onAnalyze: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        if (!hasAudioPermission) {
            PermissionPanel(requestPermissions)
        }
        StatusPanel(state)
        MicLevelPanel(state)
        ControlPanel(
            state = state,
            hasAudioPermission = hasAudioPermission,
            onStart = onStart,
            onPause = onPause,
            onResume = onResume,
            onEnd = onEnd,
            onAnalyze = onAnalyze,
            onToggleMute = onToggleMute,
        )
        CaptionPanel(state.liveCaption)
        InsightPanel(state.latestInsights)
        ResearchPanel(researchTasks)
        SummaryPanel(state.currentSummary, state.latestError)
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("翎绛", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("实时听觉分析助手", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun PermissionPanel(requestPermissions: () -> Unit) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要麦克风权限才能开始监听。", fontWeight = FontWeight.SemiBold)
            Button(onClick = requestPermissions) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text("授权麦克风")
            }
        }
    }
}

@Composable
private fun MicLevelPanel(state: SessionUiState) {
    val level = (state.micRms / 900.0).toFloat().coerceIn(0f, 1f)
    val usesSystemSpeech = state.asrModeLabel == "系统识别"
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("麦克风输入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        usesSystemSpeech && state.status != SessionStatus.Idle -> "系统接管"
                        state.isSpeechDetected -> "有声音"
                        else -> "较安静"
                    },
                    color = if (state.isSpeechDetected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (usesSystemSpeech && state.status != SessionStatus.Idle) {
                LinearProgressIndicator(progress = 1f, modifier = Modifier.fillMaxWidth())
                Text(
                    "当前由系统语音服务直接采集，App 无法读取系统服务的音量数值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(progress = level, modifier = Modifier.fillMaxWidth())
                Text(
                    "RMS %.0f · Peak %d · 活跃 %.1f%%".format(
                        state.micRms,
                        state.micPeak,
                        state.micActiveRatio * 100.0,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                state.transcriptionStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StatusPanel(state: SessionUiState) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(statusText(state.status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(formatDuration(state.elapsedMillis), style = MaterialTheme.typography.titleLarge)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (state.asrConfigured) state.asrModeLabel else "ASR 未配置") })
                if (state.asrConfigured && !state.asrPortable) {
                    AssistChip(onClick = {}, label = { Text("非外出模式") })
                }
                AssistChip(onClick = {}, label = { Text(if (state.llmConfigured) state.llmModeLabel else "LLM 未配置") })
                AssistChip(onClick = {}, label = { Text(if (state.isAnalysisMuted) "静音分析" else "文字建议开启") })
                if (state.isAnalyzing) AssistChip(onClick = {}, label = { Text("分析中") })
            }
        }
    }
}

@Composable
private fun CaptionPanel(caption: String) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("实时字幕", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = caption.ifBlank { "点击开始后，这里会显示低延迟转写字幕。" },
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
                color = if (caption.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SummaryPanel(summary: String, error: String?) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前摘要与提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = summary.ifBlank { "翎绛会在关键事件出现时更新摘要、风险和追问建议。" },
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
                color = if (summary.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (!error.isNullOrBlank()) {
                Text("状态：$error", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun InsightPanel(insights: List<LiveInsight>) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("专项提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (insights.isEmpty()) {
                Text(
                    "会优先提示责任、时间、价格、风险、技术取舍和追问点。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                insights.take(5).forEach { insight ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${insight.priority}/${insight.category} · ${insight.factType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(insight.text)
                        if (insight.evidence.isNotBlank()) {
                            Text(
                                "证据：${insight.evidence}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResearchPanel(tasks: List<ResearchTaskEntity>) {
    if (tasks.isEmpty()) return
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("后台资料卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            tasks.take(3).forEach { task ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${task.status} · ${task.query.take(42)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val detail = when {
                        task.summary.isNotBlank() -> task.summary.take(160)
                        !task.errorMessage.isNullOrBlank() -> task.errorMessage
                        else -> "正在后台核验资料"
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlPanel(
    state: SessionUiState,
    hasAudioPermission: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onAnalyze: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            when (state.status) {
                SessionStatus.Idle, SessionStatus.Reviewing, SessionStatus.Error -> {
                    ElevatedButton(
                        onClick = onStart,
                        enabled = hasAudioPermission,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("开始")
                    }
                }
                SessionStatus.Paused -> {
                    ElevatedButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("继续")
                    }
                }
                else -> {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Text("暂停")
                    }
                }
            }
            Button(
                onClick = onEnd,
                enabled = state.activeSessionId != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = null)
                Text("挂断")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onAnalyze, enabled = state.activeSessionId != null) {
                Icon(Icons.Default.Psychology, contentDescription = null)
                Text("分析当前局势")
            }
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (state.isAnalysisMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (state.isAnalysisMuted) "取消静音分析" else "静音分析",
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun statusText(status: SessionStatus): String = when (status) {
    SessionStatus.Idle -> "待机"
    SessionStatus.Listening -> "监听中"
    SessionStatus.Analyzing -> "分析中"
    SessionStatus.Alerting -> "提醒中"
    SessionStatus.Paused -> "暂停中"
    SessionStatus.Reviewing -> "复盘中"
    SessionStatus.Error -> "异常"
}
