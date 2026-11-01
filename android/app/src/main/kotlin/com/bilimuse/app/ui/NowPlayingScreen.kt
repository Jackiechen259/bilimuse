package com.bilimuse.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.bilimuse.app.R
import com.bilimuse.app.ShufflePoolState
import com.bilimuse.app.btr.EngineSnapshot
import com.bilimuse.app.player.PlayerUiState
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    shufflePool: ShufflePoolState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeatMode: (Int) -> Unit,
    onShuffle: (Boolean) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AppBackgroundBrush)
            // 盖住下层迷你播放器：点空白处不应穿透到下面的按钮
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        // 左上角的一团粉色光晕，纯装饰
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-180).dp)
                .size(460.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(BiliPink.copy(alpha = 0.16f), Color.Transparent),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = BiliText,
                    )
                }
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.labelLarge,
                    color = BiliMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Box(
                    Modifier.widthIn(min = 48.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    val quality = state.info?.quality
                    if (!quality.isNullOrBlank()) InfoChip(quality, BiliPink)
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 360.dp)
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(26.dp),
                        ambientColor = BiliPink.copy(alpha = 0.25f),
                        spotColor = BiliPink.copy(alpha = 0.3f),
                    )
                    .clip(RoundedCornerShape(26.dp))
                    .background(CoverFallbackBrush),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "♪",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (state.cover.isNotBlank()) {
                    AsyncImage(
                        model = state.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = state.title.ifBlank { "未在播放" },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.upper.ifBlank { "B站收藏夹" },
                style = MaterialTheme.typography.bodyMedium,
                color = BiliMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(18.dp))

            SeekSection(state = state, onSeek = onSeek)

            Spacer(Modifier.height(8.dp))

            Controls(
                state = state,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
                onRepeatMode = onRepeatMode,
                onShuffle = onShuffle,
            )

            if (state.shuffle) {
                Spacer(Modifier.height(10.dp))
                ShuffleScopeHint(shufflePool)
            }

            Spacer(Modifier.height(22.dp))

            EngineCard(state)
            Spacer(Modifier.height(14.dp))

            val info = state.info
            Text(
                text = buildString {
                    if (info != null) {
                        append(formatSize(info.size))
                        append(" · ")
                        append(info.codecs.ifBlank { info.mime }.ifBlank { "音频" })
                        append(" · ")
                    }
                    append(state.bvid)
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SeekSection(state: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val fraction = if (duration > 0L) {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shown = if (dragging) dragValue else fraction
    val positionMs = if (dragging) (dragValue * duration).toLong() else state.positionMs

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                if (duration > 0L) onSeek((dragValue * duration).toLong())
            },
            enabled = duration > 0L,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = BiliPink,
                activeTrackColor = BiliPink,
                inactiveTrackColor = BiliLine,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
            Text(
                text = if (state.isBuffering) "缓冲中…" else formatTime(duration),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.isBuffering) BiliBlue else BiliMuted,
            )
        }
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRepeatMode: (Int) -> Unit,
    onShuffle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { onShuffle(!state.shuffle) }) {
            Icon(
                painter = painterResource(R.drawable.ic_shuffle),
                contentDescription = "随机播放",
                tint = if (state.shuffle) BiliPink else BiliMuted,
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_previous),
                contentDescription = "上一首",
                tint = BiliText,
                modifier = Modifier.size(30.dp),
            )
        }
        Surface(
            shape = CircleShape,
            color = BiliPink,
            modifier = Modifier
                .size(70.dp)
                .clickable(onClick = onTogglePlay),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    ),
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color(0xFF1A0A12),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        IconButton(onClick = onNext) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = "下一首",
                tint = BiliText,
                modifier = Modifier.size(30.dp),
            )
        }
        val repeatIcon = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
            R.drawable.ic_repeat_one
        } else {
            R.drawable.ic_repeat
        }
        IconButton(
            onClick = {
                val next = when (state.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                onRepeatMode(next)
            },
        ) {
            Icon(
                painter = painterResource(repeatIcon),
                contentDescription = "循环模式",
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) BiliMuted else BiliPink,
            )
        }
    }
}

@Composable
private fun ShuffleScopeHint(pool: ShufflePoolState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            pool.preparing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = BiliPink,
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = buildString {
                        append("正在加载")
                        append(pool.scope)
                        when {
                            pool.total > 0 -> append(" ${pool.loaded}/${pool.total}")
                            pool.loaded > 0 -> append(" · 已加载 ${pool.loaded} 首")
                        }
                        append("…")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
            }
            pool.error != null -> Text(
                text = if (pool.loaded > 0) {
                    "加载失败，随机仅限已加载的 ${pool.loaded} 首"
                } else {
                    "加载失败，随机仍限已加载内容"
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliDanger,
                textAlign = TextAlign.Center,
            )
            pool.ready -> Text(
                text = "随机范围：${pool.scope} · ${pool.loaded} 首",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                textAlign = TextAlign.Center,
            )
            else -> Text(
                text = "随机范围：已加载内容",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EngineCard(state: PlayerUiState) {
    val engine = state.engine
    val info = state.info
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BiliPanel,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BiliLine),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BTR 引擎", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(8.dp))
                when {
                    engine == null -> InfoChip("解析中", BiliMuted)
                    engine.mode == "btr" -> InfoChip("BTR", BiliPink)
                    else -> InfoChip("单连接", BiliBlue)
                }
                Spacer(Modifier.weight(1f))
                if (engine != null) {
                    Text(
                        text = "${engine.nodes.size} 节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliMuted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                engine == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BiliPink,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "正在探测 CDN 节点…",
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliMuted,
                    )
                }
                engine.mode == "btr" -> BtrDetails(engine)
                else -> SingleDetails(engine)
            }

            if (info != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = BiliLine)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${info.quality} · ${formatSize(info.size)} · " +
                        info.codecs.ifBlank { info.mime }.ifBlank { "音频" },
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
            }
        }
    }
}

@Composable
private fun BtrDetails(engine: EngineSnapshot) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Metric("并发", "${engine.concurrency}", Modifier.weight(1f))
            Metric("块进度", "${engine.chunksDone}/${engine.chunksTotal}", Modifier.weight(1f))
            Metric("对冲", "${engine.hedges}", Modifier.weight(1f))
            Metric("重试", "${engine.retries}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        ProgressLine(engine.progress.toFloat())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "已下载 ${(engine.progress * 100).roundToInt()}% · ${formatSize(engine.bytesDone)}",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatSpeed(engine.recentSpeed),
                style = MaterialTheme.typography.bodySmall,
                color = BiliPink,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(10.dp))
        engine.nodes.take(3).forEach { node ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    shortHost(node.host),
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (node.speed > 0) formatSpeed(node.speed) else "待测速",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (node.errors > 0) BiliDanger else BiliMuted,
                )
            }
        }
    }
}

@Composable
private fun SingleDetails(engine: EngineSnapshot) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "BTR 已关闭，单连接直通",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatSpeed(engine.recentSpeed),
                style = MaterialTheme.typography.bodySmall,
                color = BiliBlue,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(engine.progress.toFloat(), color = BiliBlue)
        Spacer(Modifier.height(8.dp))
        Text(
            "已读取 ${formatSize(engine.bytesDone)}",
            style = MaterialTheme.typography.bodySmall,
            color = BiliMuted,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = BiliText,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BiliMuted,
        )
    }
}
