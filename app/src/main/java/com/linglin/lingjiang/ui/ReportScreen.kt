package com.linglin.lingjiang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linglin.lingjiang.data.SessionRepository

@Composable
fun ReportScreen(
    repository: SessionRepository,
    sessionId: String,
    onBack: () -> Unit,
) {
    val session by repository.observeSession(sessionId).collectAsState(initial = null)
    val transcripts by repository.observeTranscripts(sessionId).collectAsState(initial = emptyList())
    val insights by repository.observeInsights(sessionId).collectAsState(initial = emptyList())
    val researchTasks by repository.observeResearchTasks(sessionId).collectAsState(initial = emptyList())
    val report by repository.observeFinalReport(sessionId).collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text("会话报告", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(session?.title ?: "会话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            session?.let { "${formatDateTime(it.startedAt)} · ${formatDuration(it.durationMillis)} · ${it.status}" } ?: "加载中",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(report?.overview ?: session?.oneLineSummary ?: "报告生成后会显示总览。")
                    }
                }
            }
            item {
                SectionCard(title = "最终报告") {
                    Text(report?.contentMarkdown ?: session?.finalReport?.ifBlank { "挂断后会生成完整报告。" } ?: "挂断后会生成完整报告。")
                }
            }
            item {
                SectionCard(title = "实时建议回顾") {
                    if (insights.isEmpty()) {
                        Text("暂无实时建议。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            insights.take(20).forEach {
                                Text("• ${it.priority}/${it.category}：${it.text} ${if (it.evidence.isBlank()) "" else "｜证据：${it.evidence}"}")
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = "后台资料卡") {
                    if (researchTasks.isEmpty()) {
                        Text("暂无后台资料卡。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            researchTasks.take(20).forEach {
                                Text("• ${it.status}：${it.query}")
                                val body = it.summary.ifBlank { it.errorMessage.orEmpty() }
                                if (body.isNotBlank()) {
                                    Text(
                                        body.take(260),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (it.sources.isNotBlank()) {
                                    Text(
                                        it.sources.take(260),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("完整转写", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(transcripts, key = { it.id }) { segment ->
                Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOfNotNull(
                                formatOffset(segment.startMillis),
                                segment.speakerLabel.ifBlank { "说话人待确认" },
                                segment.speakerConfidence?.let { "说话人 %.0f%%".format(it * 100) },
                                segment.intentLabel.takeIf { it.isNotBlank() && it != "未判断" }?.let { "意图 $it" },
                                segment.intentConfidence?.let { "意图 %.0f%%".format(it * 100) },
                                segment.source,
                                segment.confidence?.let { "转写 %.0f%%".format(it * 100) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(segment.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
