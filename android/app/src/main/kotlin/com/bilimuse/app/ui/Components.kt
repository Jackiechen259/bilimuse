package com.bilimuse.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bilimuse.app.R
import com.bilimuse.app.player.PlayerUiState
import kotlin.math.absoluteValue

/**
 * 带按下回弹的图标键：用于顶栏、播放页走带和弹层关闭键。
 *
 * 保留系统涟漪（[LocalIndication]），按下时只做 0.86 的图层缩放，不改变点击区域。
 */
@Composable
fun MotionIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = BiliText,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .pressScale(interactionSource, pressedScale = 0.86f)
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            content()
        }
    }
}

fun formatTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1024 -> "%.2f GB".format(mb / 1024)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return "0 KB/s"
    val mb = bytesPerSecond / 1024.0 / 1024.0
    return if (mb >= 1) "%.1f MB/s".format(mb) else "%.0f KB/s".format(mb * 1024)
}

fun formatSeconds(seconds: Int): String = formatTime(seconds * 1000L)

fun shortHost(host: String): String = when {
    host.isBlank() -> "节点"
    host.contains("demo.bilimuse") -> "本地演示节点"
    else -> host
        .removeSuffix(".bilivideo.com")
        .removeSuffix(".akamaized.net")
        .removePrefix("upos-sz-")
        .removePrefix("upos-hz-")
}

@Composable
fun CoverArt(
    cover: String,
    size: Dp,
    corner: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(CoverFallbackBrush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "♪",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.Bold,
        )
        if (cover.isNotBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun PlayingBars(
    active: Boolean,
    color: Color = BiliPink,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val animate = motion.enabled && active
    val levels = if (animate) {
        val transition = rememberInfiniteTransition(label = "playing-bars")
        val first by transition.animateFloat(
            initialValue = 0.22f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(460, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "bar1",
        )
        val second by transition.animateFloat(
            initialValue = 0.95f,
            targetValue = 0.26f,
            animationSpec = infiniteRepeatable(
                tween(620, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "bar2",
        )
        val third by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                tween(360, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "bar3",
        )
        listOf(first, second, third)
    } else if (active) {
        // 系统关闭动画时仍然保留「正在播放」的静态标识。
        listOf(0.92f, 0.62f, 0.8f)
    } else {
        listOf(0.32f, 0.22f, 0.28f)
    }
    Canvas(modifier.size(18.dp, 16.dp)) {
        val barWidth = size.width / 5f
        val gap = barWidth / 2f
        levels.forEachIndexed { index, level ->
            val barHeight = (size.height * level).coerceAtLeast(2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
fun InfoChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
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
            enabled = enabled,
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
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = BiliPink,
    track: Color = Color.White.copy(alpha = 0.07f),
    resetKey: Any? = Unit,
) {
    val motion = LocalMotion.current
    val target = progress.coerceIn(0f, 1f)
    // resetKey 变化（换歌）时直接对齐新进度，避免旧进度条倒着退回去。
    val value = remember(resetKey) { Animatable(target) }
    LaunchedEffect(target, resetKey, motion.enabled) {
        if (motion.enabled) {
            // 进度回传是 500ms 一跳，用同长的线性补间把跳跃磨平；暂停 / 拖动时也不会过冲。
            value.animateTo(target, motion.tween(560, easing = LinearEasing))
        } else {
            value.snapTo(target)
        }
    }
    Box(
        modifier = modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(track),
    ) {
        if (value.value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(value.value)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.72f), color),
                        ),
                    ),
            )
        }
    }
}

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    val motion = LocalMotion.current
    val progress = if (state.durationMs > 0L) {
        state.positionMs.toFloat() / state.durationMs
    } else {
        0f
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        BiliPanel.copy(alpha = 0.99f),
                        BiliPanel2.copy(alpha = 0.98f),
                    ),
                ),
            )
            .navigationBarsPadding(),
    ) {
        ProgressLine(progress, Modifier.fillMaxWidth(), color = BiliPink, resetKey = state.bvid)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = state.cover,
                transitionSpec = {
                    (fadeIn(motion.tween(360, delayMs = 40, easing = MotionEase.OutExpo)) +
                        scaleIn(
                            initialScale = 1.08f,
                            animationSpec = motion.tween(520, easing = MotionEase.OutExpo),
                        )).togetherWith(
                        fadeOut(motion.tween(240, easing = MotionEase.InOut)),
                    )
                },
                label = "mini-cover",
            ) { cover ->
                CoverArt(cover, 44.dp, 10.dp)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val subtitle = when {
                    state.isBuffering -> "缓冲中…"
                    state.upper.isBlank() -> "BiliMuse"
                    else -> state.upper
                }
                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = {
                        fadeIn(motion.tween(220)).togetherWith(fadeOut(motion.tween(140)))
                    },
                    label = "mini-subtitle",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isBuffering) BiliBlue else BiliMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MiniTransportButton(
                onClick = onTogglePlay,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
            ) {
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = {
                        (fadeIn(motion.tween(150)) + scaleIn(
                            initialScale = 0.62f,
                            animationSpec = motion.spring(dampingRatio = 0.55f, stiffness = 760f),
                        )).togetherWith(
                            fadeOut(motion.tween(110)) + scaleOut(
                                targetScale = 0.62f,
                                animationSpec = motion.tween(110, easing = MotionEase.In),
                            ),
                        )
                    },
                    label = "mini-play-icon",
                ) { playing ->
                    Icon(
                        painter = painterResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        contentDescription = null,
                        tint = BiliText,
                    )
                }
            }
            MiniTransportButton(
                onClick = onNext,
                contentDescription = "下一首",
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = null,
                    tint = BiliText,
                )
            }
        }
    }
}

/** 迷你播放条上的圆形图标键：按下收紧，松开回弹。 */
@Composable
private fun MiniTransportButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = BiliText,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(42.dp)
            .pressScale(interactionSource, pressedScale = 0.86f)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}

@Composable
fun SkeletonRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .skeleton(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth(0.75f)
                    .height(12.dp)
                    .skeleton(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.4f)
                    .height(10.dp)
                    .skeleton(RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
fun BrandLogo(size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(CoverFallbackBrush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "♪",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.5f).sp,
        )
    }
}

/** 把负数也安全地转成进度（避免 UI 层崩溃）。 */
fun ratio(value: Long, total: Long): Float =
    if (total <= 0L) 0f else (value.toFloat() / total).coerceIn(0f, 1f).absoluteValue
