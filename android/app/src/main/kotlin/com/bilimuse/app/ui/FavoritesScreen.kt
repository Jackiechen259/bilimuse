package com.bilimuse.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bilimuse.app.LibraryUiState
import com.bilimuse.app.data.Folder

/**
 * 收藏夹管理独立页面（不再塞进设置底部弹层）。
 *
 * 这里只决定本机要显示哪些收藏夹：书库列表和 Android Auto 都按这份隐藏列表过滤，
 * B 站账号上的收藏本身不会被修改。
 */
@Composable
fun FavoritesScreen(
    state: LibraryUiState,
    hiddenFolderIds: Set<Long>,
    onBack: () -> Unit,
    onFolderHidden: (Long, Boolean) -> Unit,
    onShowAllFolders: () -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val folders = state.allFolders
    val hiddenCount = folders.count { it.id in hiddenFolderIds }
    val manageable = state.user.loggedIn || state.demo

    Box(
        Modifier
            .fillMaxSize()
            .background(AppBackgroundBrush)
            // 盖住下层的书库列表：点空白处不应穿透到下面的播放按钮
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            FavoritesTopBar(state = state, onBack = onBack, onRefresh = onRefresh)

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    folders.isNotEmpty() -> {
                        item {
                            FavoritesSummary(
                                total = folders.size,
                                hidden = hiddenCount,
                                allHidden = hiddenCount == folders.size,
                                onShowAllFolders = onShowAllFolders,
                            )
                        }
                        item {
                            FolderListCard(
                                folders = folders,
                                hiddenFolderIds = hiddenFolderIds,
                                onFolderHidden = onFolderHidden,
                            )
                        }
                        item {
                            Text(
                                text = "隐藏只影响本机：手机上的收藏夹列表和 Android Auto " +
                                    "都不再显示它，B 站账号里的收藏不会变化。新收藏夹默认显示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = BiliMuted.copy(alpha = 0.85f),
                            )
                        }
                    }
                    state.loading -> item { LoadingState() }
                    !manageable -> item { LoginPrompt(onLogin = onLogin) }
                    else -> item { EmptyState(demo = state.demo, onRefresh = onRefresh) }
                }
            }
        }
    }
}

@Composable
private fun FavoritesTopBar(
    state: LibraryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = BiliText,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("收藏夹管理", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    state.loading -> "正在刷新收藏夹…"
                    state.demo -> "演示模式 · 只影响本机显示"
                    state.user.loggedIn -> "只影响本机显示与 Android Auto"
                    else -> "登录后可以管理收藏夹"
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
        }
        if (state.user.loggedIn || state.demo) {
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新收藏夹",
                    tint = if (state.loading) BiliMuted.copy(alpha = 0.45f) else BiliText,
                )
            }
        }
    }
}

@Composable
private fun FavoritesSummary(
    total: Int,
    hidden: Int,
    allHidden: Boolean,
    onShowAllFolders: () -> Unit,
) {
    Surface(
        color = BiliPanel2,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BiliLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("共 $total 个收藏夹", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (hidden == 0) {
                            "全部显示中"
                        } else {
                            "显示 ${total - hidden} 个 · 隐藏 $hidden 个"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hidden > 0) BiliPink else BiliMuted,
                    )
                }
                if (hidden > 0) {
                    TextButton(
                        onClick = onShowAllFolders,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text("全部显示", color = BiliPink, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "关闭开关后，该收藏夹不会出现在列表和 Android Auto 里；" +
                    "只影响本机显示，不会取消 B 站收藏。",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
            if (allHidden) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "所有收藏夹都已隐藏，书库现在是空列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliDanger,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun FolderListCard(
    folders: List<Folder>,
    hiddenFolderIds: Set<Long>,
    onFolderHidden: (Long, Boolean) -> Unit,
) {
    Surface(
        color = BiliPanel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BiliLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            folders.forEachIndexed { index, folder ->
                if (index > 0) HorizontalDivider(color = BiliLine.copy(alpha = 0.7f))
                val hidden = folder.id in hiddenFolderIds
                SettingSwitch(
                    title = folder.title.ifBlank { "未命名收藏夹" },
                    description = buildString {
                        append("${folder.count} 个视频")
                        if (hidden) append(" · 已隐藏")
                    },
                    checked = !hidden,
                    onCheckedChange = { checked -> onFolderHidden(folder.id, !checked) },
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = BiliPink, strokeWidth = 2.5.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "正在读取收藏夹…",
            style = MaterialTheme.typography.bodySmall,
            color = BiliMuted,
        )
    }
}

@Composable
private fun LoginPrompt(onLogin: () -> Unit) {
    Surface(
        color = BiliPanel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BiliLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("登录后管理收藏夹", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "隐藏的收藏夹不会出现在列表和 Android Auto 里。",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BiliPink,
                    contentColor = Color(0xFF1A0A12),
                ),
            ) { Text("登录 B 站") }
        }
    }
}

@Composable
private fun EmptyState(demo: Boolean, onRefresh: () -> Unit) {
    Surface(
        color = BiliPanel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BiliLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (demo) "演示收藏夹还没准备好" else "还没有收藏夹",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (demo) {
                    "演示音频需要一点时间生成，稍后再刷新试试。"
                } else {
                    "去 B 站收藏一些视频，再回来刷新。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRefresh,
                border = BorderStroke(1.dp, BiliLine),
            ) { Text("刷新收藏夹", color = BiliText) }
        }
    }
}
