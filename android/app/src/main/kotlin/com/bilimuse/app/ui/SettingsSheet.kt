package com.bilimuse.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bilimuse.app.data.EngineSettings
import com.bilimuse.app.data.UserInfo
import com.bilimuse.app.data.update.UpdateStatus
import com.bilimuse.app.data.update.UpdateUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    user: UserInfo,
    demo: Boolean,
    settings: EngineSettings,
    version: String,
    update: UpdateUiState,
    autoCheckUpdates: Boolean,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDemo: () -> Unit,
    onExitDemo: () -> Unit,
    onBtrEnabled: (Boolean) -> Unit,
    onConcurrency: (Int) -> Unit,
    onChunkSize: (Int) -> Unit,
    onLossless: (Boolean) -> Unit,
    onAutoCheckUpdates: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BiliPanel,
        contentColor = BiliText,
        dragHandle = { BottomSheetDragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))

            AccountCard(
                user = user,
                demo = demo,
                onLogin = onLogin,
                onLogout = onLogout,
                onDemo = onDemo,
                onExitDemo = onExitDemo,
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("播放引擎")

            SettingSwitch(
                title = "BTR 多线程",
                description = "多连接 / 多 CDN 分段拉流，跨境线路更快",
                checked = settings.btrEnabled,
                onCheckedChange = onBtrEnabled,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "并发数",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(2, 4, 8, 12, 16, 24).forEach { value ->
                    FilterChip(
                        selected = settings.concurrency == value,
                        onClick = { onConcurrency(value) },
                        enabled = settings.btrEnabled,
                        label = { Text("$value") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BiliPink.copy(alpha = 0.2f),
                            selectedLabelColor = BiliPink,
                        ),
                    )
                }
            }
            Text(
                "节点本身很快时会自动降低并发；出现慢块再逐渐拉满",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "分块大小",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(256 * 1024 to "256 KB", 512 * 1024 to "512 KB", 1024 * 1024 to "1 MB")
                    .forEach { (bytes, label) ->
                        FilterChip(
                            selected = settings.chunkSize == bytes,
                            onClick = { onChunkSize(bytes) },
                            enabled = settings.btrEnabled,
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BiliPink.copy(alpha = 0.2f),
                                selectedLabelColor = BiliPink,
                            ),
                        )
                    }
            }

            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = "无损优先",
                description = "有 Hi-Res 或杜比音轨就用（需要大会员）",
                checked = settings.preferLossless,
                onCheckedChange = onLossless,
            )

            Spacer(Modifier.height(8.dp))
            Surface(
                color = if (settings.btrEnabled) {
                    BiliPink.copy(alpha = 0.08f)
                } else {
                    BiliBlue.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (settings.btrEnabled) BiliPink.copy(alpha = 0.22f)
                    else BiliBlue.copy(alpha = 0.22f),
                ),
            ) {
                Text(
                    text = if (settings.btrEnabled) {
                        "BTR 已开启 · ${settings.concurrency} 并发 · " +
                            "${settings.chunkSize / 1024} KB/块"
                    } else {
                        "BTR 已关闭，单连接直通"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.btrEnabled) BiliPink else BiliBlue,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            SectionTitle("关于")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "BiliMuse Android $version",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = updateStatusText(update),
                        style = MaterialTheme.typography.bodySmall,
                        color = updateStatusColor(update),
                    )
                }
                Spacer(Modifier.size(10.dp))
                OutlinedButton(
                    onClick = if (update.hasOpenableUpdate) onOpenUpdate else onCheckUpdate,
                    enabled = update.status != UpdateStatus.CHECKING,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (update.status == UpdateStatus.AVAILABLE || update.status == UpdateStatus.READY) {
                            BiliPink.copy(alpha = 0.55f)
                        } else {
                            BiliLine
                        },
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = updateActionLabel(update),
                        color = if (update.status == UpdateStatus.AVAILABLE || update.status == UpdateStatus.READY) {
                            BiliPink
                        } else {
                            BiliText
                        },
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SettingSwitch(
                title = "自动检查更新",
                description = "启动时查询 GitHub Releases，每 6 小时最多一次",
                checked = autoCheckUpdates,
                onCheckedChange = onAutoCheckUpdates,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "把 B 站收藏夹变成私人音乐库，内置 BTR 多线程 / 多 CDN 流式加速。" +
                    "引擎、登录态与播放缓存都只在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "音频来自 B 站公开接口，仅供本人账号收藏内容在线收听，请遵守 B 站用户协议。",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted.copy(alpha = 0.8f),
            )

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = BiliLine)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onDismiss) {
                    Text("完成", color = BiliPink)
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    user: UserInfo,
    demo: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDemo: () -> Unit,
    onExitDemo: () -> Unit,
) {
    Surface(
        color = BiliPanel2,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BiliLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (demo) CoverFallbackBrush
                            else Brush.linearGradient(
                                listOf(Color(0xFF2B3242), Color(0xFF1A1F2B)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        demo -> Text("♪", color = Color.White, fontWeight = FontWeight.Bold)
                        user.face.isNotBlank() -> AsyncImage(
                            model = user.face,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Text(
                            text = (user.uname.ifBlank { "B" }).take(1),
                            color = BiliText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = when {
                            demo -> "演示模式"
                            user.loggedIn -> user.uname.ifBlank { "已登录" }
                            else -> "未登录"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            demo -> "本地合成音轨，不需要登录"
                            user.loggedIn && user.vip -> "UID ${user.mid} · 大会员"
                            user.loggedIn -> "UID ${user.mid}"
                            else -> "登录后读取你的收藏夹"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliMuted,
                    )
                }
                when {
                    demo -> OutlinedButton(
                        onClick = onExitDemo,
                        border = BorderStroke(1.dp, BiliLine),
                    ) { Text("退出演示", color = BiliText, fontSize = 12.sp) }
                    user.loggedIn -> OutlinedButton(
                        onClick = onLogout,
                        border = BorderStroke(1.dp, BiliLine),
                    ) { Text("退出登录", color = BiliText, fontSize = 12.sp) }
                    else -> Button(
                        onClick = onLogin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BiliPink,
                            contentColor = Color(0xFF1A0A12),
                        ),
                    ) { Text("登录 B 站", fontSize = 12.sp) }
                }
            }
            if (!user.loggedIn || demo) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (demo) {
                        TextButton(onClick = onLogin) {
                            Text("登录 B 站", color = BiliPink, fontSize = 12.sp)
                        }
                    } else {
                        TextButton(onClick = onDemo) {
                            Text("先玩演示模式（无需登录）", color = BiliMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BiliPink,
                uncheckedThumbColor = BiliMuted,
                uncheckedTrackColor = BiliLine,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = BiliMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun BottomSheetDragHandle() {
    Box(
        Modifier
            .padding(vertical = 10.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(BiliLine),
    )
}

private fun updateStatusText(update: UpdateUiState): String = when (update.status) {
    UpdateStatus.IDLE -> "可从 GitHub Releases 获取新版本"
    UpdateStatus.CHECKING -> "正在检查更新…"
    UpdateStatus.NO_RELEASE -> "GitHub 上还没有发布版本"
    UpdateStatus.UP_TO_DATE -> "已是最新版本"
    UpdateStatus.AVAILABLE -> "发现新版本 v${update.update?.version.orEmpty()}"
    UpdateStatus.DOWNLOADING -> "正在下载 ${(update.progress * 100).toInt()}%"
    UpdateStatus.READY -> "更新包已下载，可以安装"
    UpdateStatus.FAILED -> update.error ?: "更新失败"
}

private fun updateActionLabel(update: UpdateUiState): String = when (update.status) {
    UpdateStatus.IDLE -> "检查更新"
    UpdateStatus.CHECKING -> "检查中"
    UpdateStatus.NO_RELEASE, UpdateStatus.UP_TO_DATE -> "重新检查"
    UpdateStatus.AVAILABLE -> "查看更新"
    UpdateStatus.DOWNLOADING -> "查看进度"
    UpdateStatus.READY -> "安装更新"
    UpdateStatus.FAILED -> if (update.update != null) "查看" else "重试"
}

private fun updateStatusColor(update: UpdateUiState): Color = when (update.status) {
    UpdateStatus.AVAILABLE,
    UpdateStatus.DOWNLOADING,
    UpdateStatus.READY,
    -> BiliPink
    UpdateStatus.FAILED -> BiliDanger
    else -> BiliMuted
}
