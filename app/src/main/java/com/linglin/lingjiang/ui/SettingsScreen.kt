package com.linglin.lingjiang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linglin.lingjiang.BuildConfig
import com.linglin.lingjiang.session.SessionUiState

@Composable
fun SettingsScreen(uiState: SessionUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth()) {
                ConfigItem(
                    title = "语音转写",
                    description = if (uiState.asrConfigured) {
                        "${uiState.asrModeLabel} · ${BuildConfig.ASR_MODEL}"
                    } else {
                        "未注入 LINGJIANG_ASR_*"
                    },
                    configured = uiState.asrConfigured,
                )
                if (uiState.asrConfigured && !uiState.asrPortable) {
                    ListItem(
                        headlineContent = { Text("外出可用性") },
                        supportingContent = { Text("当前是 PC 开发桥模式，离开电脑或断开 USB 后不能转写。正式开会需要配置公网远端 ASR。") },
                        leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                    )
                }
                ConfigItem(
                    title = "实时分析",
                    description = if (uiState.llmConfigured) {
                        "${uiState.llmModeLabel} · ${BuildConfig.LLM_MODEL}"
                    } else {
                        "未注入 LINGJIANG_LLM_*"
                    },
                    configured = uiState.llmConfigured,
                )
                ListItem(
                    headlineContent = { Text("耳机语音播报") },
                    supportingContent = { Text("首轮保留路由占位，默认关闭。") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                    trailingContent = { Switch(checked = false, onCheckedChange = null, enabled = false) },
                )
            }
        }
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("隐私边界", fontWeight = FontWeight.SemiBold)
                Text("翎绛只采集手机麦克风能听到的周边声音，不承诺读取系统电话对方音频。涉及他人对话时，请遵守当地法律和告知义务。")
            }
        }
    }
}

@Composable
private fun ConfigItem(title: String, description: String, configured: Boolean) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description.ifBlank { "未配置" }) },
        leadingContent = {
            Icon(
                imageVector = if (configured) Icons.Default.CloudDone else Icons.Default.CloudOff,
                contentDescription = null,
                tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
    )
}
