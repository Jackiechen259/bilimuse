package com.bilimuse.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.bilimuse.app.player.PlayQueueUiState
import com.bilimuse.app.player.PlayerUiState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    queue: PlayQueueUiState,
    showBtrDetails: Boolean,
    shufflePool: ShufflePoolState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeatMode: (Int) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onLoadMoreQueue: () -> Unit,
) {
    val motion = LocalMotion.current
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    val accent = rememberCoverAccent(state.cover)
    val tilt = rememberCoverTilt(enabled = state.isPlaying)

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
        AuroraGlow(accent.color, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NowPlayingTopBar(
                state = state,
                queue = queue,
                accent = accent.color,
                onBack = onBack,
                onOpenQueue = { queueOpen = true },
            )

            Spacer(Modifier.height(16.dp))

            CoverHero(
                cover = state.cover,
                accent = accent,
                tilt = tilt,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 360.dp)
                    .aspectRatio(1f)
                    .staggerIn(index = 1, distance = 18.dp, scaleFrom = 0.93f),
            )

            Spacer(Modifier.height(24.dp))

            TrackTitles(state = state, modifier = Modifier.staggerIn(index = 2, distance = 12.dp))

            Spacer(Modifier.height(18.dp))

            SeekSection(
                state = state,
                accent = accent.color,
                onSeek = onSeek,
                modifier = Modifier.staggerIn(index = 3, distance = 10.dp),
            )

            Spacer(Modifier.height(8.dp))

            Controls(
                state = state,
                accent = accent.color,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrevious = onPrevious,
                onRepeatMode = onRepeatMode,
                onShuffle = onShuffle,
                modifier = Modifier.staggerIn(index = 4, distance = 12.dp),
            )

            if (state.shuffle) {
                Spacer(Modifier.height(10.dp))
                ShuffleScopeHint(
                    pool = shufflePool,
                    modifier = Modifier.staggerIn(index = 5, distance = 8.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            AnimatedVisibility(
                visible = showBtrDetails,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = motion.spring(dampingRatio = 0.9f, stiffness = 300f),
                ) + fadeIn(motion.tween(220)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = motion.tween(260, easing = MotionEase.InOut),
                ) + fadeOut(motion.tween(160)),
            ) {
                Column {
                    EngineCard(state)
                    Spacer(Modifier.height(14.dp))
                }
            }

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
                modifier = Modifier.staggerIn(index = 6, distance = 6.dp),
            )
            Spacer(Modifier.height(20.dp))
        }

        if (queueOpen) {
            PlayQueueSheet(
                state = queue,
                isPlaying = state.isPlaying,
                onDismiss = { queueOpen = false },
                onPlayIndex = onPlayQueueIndex,
                onLoadMore = onLoadMoreQueue,
            )
        }
    }
}

@Composable
private fun NowPlayingTopBar(
    state: PlayerUiState,
    queue: PlayQueueUiState,
    accent: Color,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val motion = LocalMotion.current
    val quality = state.info?.quality.orEmpty()
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .staggerIn(index = 0, distance = 6.dp),
    ) {
        MotionIconButton(
            onClick = onBack,
            contentDescription = "返回",
            tint = BiliText,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = BiliText,
            )
        }
        Text(
            text = "正在播放",
            style = MaterialTheme.typography.labelLarge,
            color = BiliMuted,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = quality.isNotBlank(),
                enter = fadeIn(motion.tween(180)) + scaleIn(
                    initialScale = 0.8f,
                    animationSpec = motion.spring(dampingRatio = 0.6f, stiffness = 640f),
                ),
                exit = fadeOut(motion.tween(120)) + scaleOut(
                    targetScale = 0.8f,
                    animationSpec = motion.tween(140, easing = MotionEase.In),
                ),
            ) {
                Box(Modifier.padding(end = 2.dp)) {
                    InfoChip(quality, accent)
                }
            }
            MotionIconButton(
                onClick = onOpenQueue,
                contentDescription = "播放队列，共 ${queue.items.size} 首",
                enabled = queue.items.isNotEmpty(),
                tint = if (queue.items.isNotEmpty()) BiliText else BiliMuted.copy(alpha = 0.35f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_queue_music),
                    contentDescription = null,
                    tint = if (queue.items.isNotEmpty()) BiliText else BiliMuted.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
private fun CoverHero(
    cover: String,
    accent: CoverAccent,
    tilt: CoverTiltState,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val shape = RoundedCornerShape(26.dp)
    val sheen = remember(cover) { Animatable(0f) }
    val currentCover = cover

    LaunchedEffect(cover, motion.enabled) {
        if (!motion.enabled) return@LaunchedEffect
        sheen.snapTo(0f)
        delay(160)
        sheen.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 820,
                easing = MotionEase.OutQuart,
            ),
        )
    }

    Box(
        modifier
            .coverTilt(tilt)
            .shadow(
                elevation = 28.dp,
                shape = shape,
                ambientColor = accent.color.copy(alpha = 0.3f),
                spotColor = accent.color.copy(alpha = 0.36f),
                clip = false,
            )
            .clip(shape)
            .background(BiliPanel)
            .background(CoverFallbackBrush),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = cover,
            transitionSpec = {
                (fadeIn(motion.tween(420, delayMs = 60, easing = MotionEase.OutExpo)) +
                    scaleIn(
                        initialScale = 1.035f,
                        animationSpec = motion.tween(700, easing = MotionEase.OutQuart),
                    )).togetherWith(
                    fadeOut(motion.tween(320, easing = MotionEase.InOut)) +
                        scaleOut(
                            targetScale = 0.99f,
                            animationSpec = motion.tween(420, easing = MotionEase.InOut),
                        ),
                )
            },
            contentAlignment = Alignment.Center,
            label = "now-cover",
            modifier = Modifier.fillMaxSize(),
        ) { animatedCover ->
            if (animatedCover.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "♪",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 76.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                AsyncImage(
                    model = animatedCover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { result ->
                        // 交叉溶解期间旧封面可能晚到，只接受当前曲目的回调。
                        if (animatedCover == currentCover) {
                            accent.onCoverLoaded(result.result.drawable)
                        }
                    },
                )
            }
        }

        // 随倾斜移动的高光 + 环境光；绘制阶段读取传感器状态，不触发重组。
        Box(Modifier.matchParentSize().coverGlare(tilt, accent.color))

        if (motion.enabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = ((sheen.value * 3f) - 1.5f) * size.width
                        alpha = (1f - abs(sheen.value * 2f - 1f)).coerceIn(0f, 1f)
                        rotationZ = 18f
                    }
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.16f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        )
    }
}

@Composable
private fun TrackTitles(state: PlayerUiState, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val title = state.title.ifBlank { "未在播放" }
    val artist = state.upper.ifBlank { "B站收藏夹" }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (fadeIn(motion.tween(300, easing = MotionEase.OutExpo)) + slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = motion.tween(380, easing = MotionEase.OutExpo),
                )).togetherWith(
                    fadeOut(motion.tween(150)) + slideOutVertically(
                        targetOffsetY = { -it / 4 },
                        animationSpec = motion.tween(200, easing = MotionEase.In),
                    ),
                )
            },
            contentAlignment = Alignment.Center,
            label = "now-title",
            modifier = Modifier.fillMaxWidth(),
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = artist,
            transitionSpec = {
                (fadeIn(motion.tween(340, delayMs = 40)) + slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = motion.tween(380, easing = MotionEase.OutExpo),
                )).togetherWith(
                    fadeOut(motion.tween(160)) + slideOutVertically(
                        targetOffsetY = { -it / 4 },
                        animationSpec = motion.tween(200, easing = MotionEase.In),
                    ),
                )
            },
            contentAlignment = Alignment.Center,
            label = "now-artist",
            modifier = Modifier.fillMaxWidth(),
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = BiliMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekSection(
    state: PlayerUiState,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val fraction = if (duration > 0L) {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    // 500ms 一跳的进度用 600ms 线性补间磨平；拖动时直接跟随手指，不补间。
    // 换歌时重建 Animatable，进度直接落到新曲目的位置，而不是从上一首倒着扫回去。
    val smoothed = remember(state.bvid) { Animatable(if (duration > 0L) fraction else 0f) }
    LaunchedEffect(fraction, state.bvid, motion.enabled) {
        if (motion.enabled) {
            smoothed.animateTo(fraction, motion.tween(600, easing = LinearEasing))
        } else {
            smoothed.snapTo(fraction)
        }
    }
    val shown = if (dragging) dragValue else smoothed.value
    val positionMs = if (dragging) (dragValue * duration).toLong() else state.positionMs
    val positionColor by animateColorAsState(
        targetValue = if (dragging) accent else BiliMuted,
        animationSpec = motion.tween(160),
        label = "seek-position-color",
    )
    val dragScale by animateFloatAsState(
        targetValue = if (dragging) 1.08f else 1f,
        animationSpec = motion.spring(dampingRatio = 0.55f, stiffness = 620f),
        label = "seek-drag-scale",
    )

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            // 自定义 thumb 比 Material 默认的大；给两端留一点内边距，
            // 拖到 0% / 100% 时圆点不会被 Slider 裁掉。
            modifier = Modifier.padding(horizontal = 9.dp),
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
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = BiliLine,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            thumb = {
                val halo by animateFloatAsState(
                    targetValue = if (dragging) 1f else 0f,
                    animationSpec = motion.tween(180),
                    label = "seek-halo",
                )
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (halo > 0.01f) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    scaleX = halo
                                    scaleY = halo
                                }
                                .background(accent.copy(alpha = 0.2f), CircleShape),
                        )
                    }
                    Box(
                        Modifier
                            .size(14.dp)
                            .graphicsLayer {
                                scaleX = dragScale
                                scaleY = dragScale
                            }
                            .shadow(
                                elevation = 10.dp,
                                shape = CircleShape,
                                ambientColor = accent,
                                spotColor = accent,
                            )
                            .background(accent, CircleShape),
                    )
                }
            },
            // 自绘轨道：隐藏 Material3 默认的末端停止点；活动轨道一直铺到圆点下面，
            // 自定义 thumb 变大时也不会出现「轨道断一截」的缝隙。
            track = { sliderState ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    ) {
                        val radius = size.height / 2f
                        drawRoundRect(
                            color = BiliLine,
                            size = size,
                            cornerRadius = CornerRadius(radius, radius),
                        )
                        val active = size.width * sliderState.value.coerceIn(0f, 1f)
                        if (active > 0.5f) {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(accent.copy(alpha = 0.72f), accent),
                                    endX = size.width.coerceAtLeast(1f),
                                ),
                                size = Size(active, size.height),
                                cornerRadius = CornerRadius(radius, radius),
                            )
                        }
                    }
                }
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = positionColor,
                modifier = Modifier.graphicsLayer {
                    scaleX = dragScale
                    scaleY = dragScale
                },
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
    accent: Color,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRepeatMode: (Int) -> Unit,
    onShuffle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val shuffleColor by animateColorAsState(
        targetValue = if (state.shuffle) accent else BiliMuted,
        animationSpec = motion.tween(220),
        label = "shuffle-tint",
    )
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MotionIconButton(
            onClick = { onShuffle(!state.shuffle) },
            contentDescription = if (state.shuffle) "关闭随机播放" else "随机播放",
            tint = shuffleColor,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shuffle),
                contentDescription = null,
                tint = shuffleColor,
            )
        }
        MotionIconButton(
            onClick = onPrevious,
            contentDescription = "上一首",
            tint = BiliText,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_previous),
                contentDescription = null,
                tint = BiliText,
                modifier = Modifier.size(30.dp),
            )
        }
        PlayPauseButton(
            isPlaying = state.isPlaying,
            accent = accent,
            onClick = onTogglePlay,
        )
        MotionIconButton(
            onClick = onNext,
            contentDescription = "下一首",
            tint = BiliText,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = null,
                tint = BiliText,
                modifier = Modifier.size(30.dp),
            )
        }
        MotionIconButton(
            onClick = {
                val next = when (state.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                onRepeatMode(next)
            },
            contentDescription = "循环模式",
            tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) BiliMuted else accent,
            modifier = Modifier.size(48.dp),
        ) {
            AnimatedContent(
                targetState = state.repeatMode,
                transitionSpec = {
                    (fadeIn(motion.tween(170)) + scaleIn(
                        initialScale = 0.55f,
                        animationSpec = motion.spring(dampingRatio = 0.55f, stiffness = 720f),
                    )).togetherWith(
                        fadeOut(motion.tween(120)) + scaleOut(
                            targetScale = 0.55f,
                            animationSpec = motion.tween(120, easing = MotionEase.In),
                        ),
                    )
                },
                label = "repeat-glyph",
            ) { mode ->
                val tint = if (mode == Player.REPEAT_MODE_OFF) BiliMuted else accent
                Icon(
                    painter = painterResource(
                        if (mode == Player.REPEAT_MODE_ONE) {
                            R.drawable.ic_repeat_one
                        } else {
                            R.drawable.ic_repeat
                        },
                    ),
                    contentDescription = null,
                    tint = tint,
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val motion = LocalMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val halo: State<Float>? = if (motion.enabled && isPlaying) {
        val transition = rememberInfiniteTransition(label = "play-halo")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 2600,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "play-halo-progress",
        )
    } else {
        null
    }

    Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        if (halo != null) {
            Canvas(Modifier.fillMaxSize()) {
                val progress = halo.value
                val radius = size.minDimension * (0.27f + progress * 0.2f)
                drawCircle(
                    color = accent.copy(alpha = (1f - progress) * 0.22f),
                    radius = radius,
                )
                drawCircle(
                    color = accent.copy(alpha = (1f - progress) * 0.08f),
                    radius = radius * 0.82f,
                )
            }
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = accent,
            contentColor = Color(0xFF1A0A12),
            interactionSource = interactionSource,
            modifier = Modifier
                .size(72.dp)
                .pressScale(interactionSource, pressedScale = 0.9f)
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    ambientColor = accent.copy(alpha = 0.7f),
                    spotColor = accent,
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(motion.tween(150)) + scaleIn(
                            initialScale = 0.5f,
                            animationSpec = motion.spring(dampingRatio = 0.5f, stiffness = 780f),
                        )).togetherWith(
                            fadeOut(motion.tween(110)) + scaleOut(
                                targetScale = 0.5f,
                                animationSpec = motion.tween(110, easing = MotionEase.In),
                            ),
                        )
                    },
                    label = "play-glyph",
                ) { playing ->
                    Icon(
                        painter = painterResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color(0xFF1A0A12),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShuffleScopeHint(pool: ShufflePoolState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
                engine.mode == "btr" -> BtrDetails(engine, state.bvid)
                else -> SingleDetails(engine, state.bvid)
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
private fun BtrDetails(engine: EngineSnapshot, resetKey: Any?) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Metric("并发", "${engine.concurrency}", Modifier.weight(1f))
            Metric("块进度", "${engine.chunksDone}/${engine.chunksTotal}", Modifier.weight(1f))
            Metric("对冲", "${engine.hedges}", Modifier.weight(1f))
            Metric("重试", "${engine.retries}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        ProgressLine(engine.progress.toFloat(), resetKey = resetKey)
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
private fun SingleDetails(engine: EngineSnapshot, resetKey: Any?) {
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
        ProgressLine(engine.progress.toFloat(), color = BiliBlue, resetKey = resetKey)
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
