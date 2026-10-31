package com.bilimuse.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bilimuse.app.data.update.UpdateStatus
import com.bilimuse.app.data.update.UpdateUiState
import java.io.File

@Composable
fun UpdateDialog(
    state: UpdateUiState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onOpenReleasePage: (String) -> Unit,
) {
    val update = state.update ?: return
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 390.dp),
            color = BiliPanel,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, BiliLine),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "自动更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = BiliMuted,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "新版本 v${update.version}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = BiliMuted)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("当前版本 v${currentVersion.removePrefix("v")}")
                        if (update.asset.size > 0L) append(" · ${formatSize(update.asset.size)}")
                        val date = update.publishedAt?.take(10)
                        if (!date.isNullOrBlank()) append(" · $date")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = BiliLine)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = update.notes.ifBlank {
                            "本次更新没有填写说明，详见 GitHub 发布页。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliText.copy(alpha = 0.9f),
                    )
                }
                Spacer(Modifier.height(14.dp))

                when (state.status) {
                    UpdateStatus.DOWNLOADING -> DownloadingSection(state, onCancelDownload)
                    UpdateStatus.READY -> ReadySection(state, onInstall, onDismiss)
                    UpdateStatus.FAILED -> FailedSection(state, onDownload, onDismiss)
                    UpdateStatus.CHECKING -> CheckingSection(onDismiss)
                    UpdateStatus.AVAILABLE -> AvailableSection(onDownload, onDismiss)
                    else -> EmptySection(onDismiss)
                }

                if (update.pageUrl.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = { onOpenReleasePage(update.pageUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看 GitHub 发布页", color = BiliMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailableSection(onDownload: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("稍后", color = BiliMuted)
            }
            Button(
                onClick = onDownload,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BiliPink,
                    contentColor = Color(0xFF1A0A12),
                ),
            ) {
                Text("下载更新")
            }
        }
        Text(
            text = "下载完成后会校验 APK 版本与签名，再交给系统安装器。",
            style = MaterialTheme.typography.bodySmall,
            color = BiliMuted.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun DownloadingSection(state: UpdateUiState, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "正在下载更新包…",
            style = MaterialTheme.typography.bodySmall,
            color = BiliText,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = BiliPink,
            trackColor = BiliLine,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = BiliPink,
            )
            Text(
                text = if (state.totalBytes > 0L) {
                    "${formatSize(state.downloadedBytes)} / ${formatSize(state.totalBytes)}"
                } else {
                    formatSize(state.downloadedBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消下载", color = BiliDanger)
        }
    }
}

@Composable
private fun ReadySection(
    state: UpdateUiState,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "更新包已下载并通过完整性校验。",
            style = MaterialTheme.typography.bodySmall,
            color = BiliBlue,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { state.apkFile?.let(onInstall) },
            enabled = state.apkFile != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BiliPink,
                contentColor = Color(0xFF1A0A12),
            ),
        ) {
            Text("安装更新")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "安装时若提示，请允许 BiliMuse 安装未知应用；装好后重新打开即可。",
            style = MaterialTheme.typography.bodySmall,
            color = BiliMuted,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("稍后安装", color = BiliMuted)
        }
    }
}

@Composable
private fun FailedSection(
    state: UpdateUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = state.error ?: "更新失败",
            style = MaterialTheme.typography.bodySmall,
            color = BiliDanger,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("关闭", color = BiliMuted)
            }
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BiliPink,
                    contentColor = Color(0xFF1A0A12),
                ),
            ) {
                Text("重试下载")
            }
        }
    }
}

@Composable
private fun CheckingSection(onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "正在检查更新…",
            style = MaterialTheme.typography.bodySmall,
            color = BiliMuted,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("关闭", color = BiliMuted)
        }
    }
}

@Composable
private fun EmptySection(onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "当前没有可安装的更新。",
            style = MaterialTheme.typography.bodySmall,
            color = BiliMuted,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("关闭", color = BiliMuted)
        }
    }
}
