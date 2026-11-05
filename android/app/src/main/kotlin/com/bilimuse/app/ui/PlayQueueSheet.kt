package com.bilimuse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilimuse.app.data.TrackItem
import com.bilimuse.app.player.PlayQueueUiState

/**
 * 播放队列面板：和桌面播放详情页右侧的队列一致。
 *
 * 手机屏幕窄，队列改成从底部拉起的面板；内容仍是当前队列、顺序 / 随机范围、
 * 当前曲目高亮、点任意行切歌、长队列懒加载、顺序播放时补下一页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayQueueSheet(
    state: PlayQueueUiState,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onLoadMore: () -> Unit,
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
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            QueueHeader(state = state, onDismiss = onDismiss)
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = BiliLine)

            if (state.items.isEmpty()) {
                QueueEmpty(Modifier.weight(1f))
            } else {
                QueueList(
                    state = state,
                    isPlaying = isPlaying,
                    onPlayIndex = onPlayIndex,
                    onLoadMore = onLoadMore,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QueueHeader(state: PlayQueueUiState, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放队列", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.countText,
                    style = MaterialTheme.typography.labelLarge,
                    color = BiliMuted,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.modeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.shuffle) BiliPink else BiliMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.positionText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.positionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliMuted,
                        maxLines = 1,
                    )
                }
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭播放队列",
                tint = BiliMuted,
            )
        }
    }
}

@Composable
private fun QueueList(
    state: PlayQueueUiState,
    isPlaying: Boolean,
    onPlayIndex: (Int) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var scrolledToCurrent by remember { mutableStateOf(false) }

    // 打开面板时把当前曲目滚进视野；之后只在当前曲目滚出视野时跟随，
    // 用户手动翻看其他位置时不打扰。
    LaunchedEffect(state.currentIndex, state.items.size) {
        val index = state.currentIndex
        if (index !in state.items.indices) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (!scrolledToCurrent || visible.none { it.index == index }) {
            val target = (index - 2).coerceAtLeast(0)
            scrolledToCurrent = true
            if (visible.isEmpty()) listState.scrollToItem(target)
            else listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(
            items = state.items,
            key = { index, item -> "${item.bvid}#$index" },
        ) { index, item ->
            QueueRow(
                item = item,
                index = index,
                current = index == state.currentIndex,
                isPlaying = isPlaying,
                onClick = { onPlayIndex(index) },
            )
        }
        item(key = "queue-footer") {
            QueueFooter(state = state, onLoadMore = onLoadMore)
        }
    }
}

@Composable
private fun QueueRow(
    item: TrackItem,
    index: Int,
    current: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) BiliPink.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClickLabel = "播放", onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (current) BiliPink else Color.Transparent),
        )
        Spacer(Modifier.width(9.dp))
        Box(
            Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (current) {
                PlayingBars(active = isPlaying)
            } else {
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        CoverArt(item.cover, 42.dp, corner = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { "未知曲目" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) BiliText else BiliText.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.upper.ifBlank { "B站收藏夹" },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.duration > 0) formatSeconds(item.duration) else "",
            style = MaterialTheme.typography.bodySmall,
            color = BiliMuted,
        )
    }
}

@Composable
private fun QueueFooter(state: PlayQueueUiState, onLoadMore: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.canLoadMore -> TextButton(
                onClick = onLoadMore,
                enabled = !state.loadingMore,
            ) {
                if (state.loadingMore) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = BiliPink,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (state.loadingMore) "正在加载下一页…" else "加载下一页",
                    color = if (state.loadingMore) BiliMuted else BiliPink,
                )
            }
            state.preparing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = BiliPink,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "正在补全随机队列…",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
            }
            else -> Text(
                text = state.endText,
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QueueEmpty(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "♪",
                color = BiliMuted.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "队列还是空的",
                style = MaterialTheme.typography.bodyMedium,
                color = BiliMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "返回列表选一首开始播放吧",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
