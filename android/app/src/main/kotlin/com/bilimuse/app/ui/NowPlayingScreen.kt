package com.bilimuse.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.drawBehind
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
import com.bilimuse.app.LyricsUiState
import com.bilimuse.app.R
import com.bilimuse.app.ShufflePoolState
import com.bilimuse.app.btr.EngineSnapshot
import com.bilimuse.app.data.LyricsCandidate
import com.bilimuse.app.data.LyricsMatch
import com.bilimuse.app.data.LyricsSourceOption
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
    lyrics: LyricsUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeatMode: (Int) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onLoadMoreQueue: () -> Unit,
    onToggleLyrics: () -> Unit,
    onLyricsOffset: (Long) -> Unit,
    onImportLyrics: () -> Unit,
    onReloadLyrics: () -> Unit,
    onCloseLyrics: () -> Unit,
    onLoadLyricsSources: suspend () -> List<LyricsSourceOption>,
    onSelectLyricsSource: (LyricsSourceOption) -> Unit,
    onSearchLyrics: suspend (String) -> List<LyricsCandidate>,
    onUseLyricsCandidate: (LyricsCandidate) -> Unit,
) {
    val motion = LocalMotion.current
    var queueOpen by rememberSaveable { mutableStateOf(false) }

    // 歌词打开时系统返回键先收起歌词，而不是直接退出播放页。
    BackHandler(enabled = lyrics.open) { onCloseLyrics() }

    Box(
        Modifier
            .fillMaxSize()
            .background(BiliBg)
            // 盖住下层迷你播放器：点空白处不应穿透到下面的按钮
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
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
                lyricsOpen = lyrics.open,
                onBack = onBack,
                onOpenQueue = { queueOpen = true },
                onToggleLyrics = onToggleLyrics,
            )

            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = lyrics.open,
                transitionSpec = {
                    (fadeIn(motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart)) +
                        scaleIn(
                            initialScale = 0.995f,
                            animationSpec = motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart),
                        )).togetherWith(
                        fadeOut(motion.tween(MotionTokens.Fast, easing = MotionEase.In)) +
                            scaleOut(
                                targetScale = 0.998f,
                                animationSpec = motion.tween(MotionTokens.Fast, easing = MotionEase.In),
                            ),
                    )
                },
                contentAlignment = Alignment.TopCenter,
                label = "lyrics-swap",
                modifier = Modifier.fillMaxWidth(),
            ) { lyricsOpen ->
                if (lyricsOpen) {
                    LyricsPanel(
                        result = lyrics.result,
                        loading = lyrics.loading,
                        error = lyrics.error,
                        positionMs = state.positionMs,
                        searchSeed = remember(state.title, state.upper) {
                            // 与网页版一致：搜索框预填清洗后的「歌名 歌手」。
                            LyricsMatch.searchQuery(state.title, state.upper)
                        },
                        resetKey = state.bvid,
                        onSeek = onSeek,
                        onOffset = onLyricsOffset,
                        onImport = onImportLyrics,
                        onReload = onReloadLyrics,
                        onClose = onCloseLyrics,
                        onLoadSources = onLoadLyricsSources,
                        onSelectSource = onSelectLyricsSource,
                        onSearch = onSearchLyrics,
                        onUseCandidate = onUseLyricsCandidate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .staggerIn(index = 1, distance = 14.dp),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CoverHero(
                            cover = state.cover,
                            playing = state.isPlaying,
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .widthIn(max = 360.dp)
                                .aspectRatio(1f)
                                .staggerIn(index = 1, distance = 18.dp, scaleFrom = 0.93f),
                        )

                        Spacer(Modifier.height(24.dp))

                        TrackTitles(
                            state = state,
                            modifier = Modifier.staggerIn(index = 2, distance = 12.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            SeekSection(
                state = state,
                onSeek = onSeek,
                modifier = Modifier.staggerIn(index = 3, distance = 10.dp),
            )

            Spacer(Modifier.height(8.dp))

            Controls(
                state = state,
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
                enter = fadeIn(motion.tween(MotionTokens.Base)),
                exit = fadeOut(motion.tween(MotionTokens.Fast)),
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
    lyricsOpen: Boolean,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onToggleLyrics: () -> Unit,
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
            LyricsToggleButton(
                active = lyricsOpen,
                onClick = onToggleLyrics,
            )
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
                    InfoChip(quality, BiliText2)
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

/** 顶栏「词」开关：选中时中性底 + 2dp 粉色底线。 */
@Composable
private fun LyricsToggleButton(
    active: Boolean,
    onClick: () -> Unit,
) {
    val motion = LocalMotion.current
    val tint by animateColorAsState(
        targetValue = if (active) BiliPinkText else BiliMuted,
        animationSpec = motion.tween(MotionTokens.Fast),
        label = "lyrics-toggle-tint",
    )
    MotionIconButton(
        onClick = onClick,
        contentDescription = "歌词",
        tint = tint,
        modifier = Modifier.size(44.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) BiliSelected else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "词",
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(if (active) BiliPinkStrong else Color.Transparent),
            )
        }
    }
}

@Composable
private fun CoverHero(
    cover: String,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val shape = RoundedCornerShape(0.dp)
    val spine = BiliPinkStrong
    Box(
        modifier
            .clip(shape)
            .background(BiliPink)
            .border(1.dp, BiliLine, shape)
            .drawBehind {
                if (playing) {
                    drawRect(
                        color = spine,
                        size = Size(2.dp.toPx(), size.height),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = cover,
            transitionSpec = {
                (fadeIn(motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart)) +
                    scaleIn(
                        initialScale = 1.015f,
                        animationSpec = motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart),
                    )).togetherWith(
                    fadeOut(motion.tween(MotionTokens.Fast, easing = MotionEase.In)),
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
                        color = BiliOnPink,
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
                )
            }
        }
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
                (fadeIn(motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart)) +
                    slideInVertically(
                        initialOffsetY = { it / 4 },
                        animationSpec = motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart),
                    )).togetherWith(
                    fadeOut(motion.tween(MotionTokens.Fast, easing = MotionEase.In)),
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
                (fadeIn(motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart)) +
                    slideInVertically(
                        initialOffsetY = { it / 4 },
                        animationSpec = motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart),
                    )).togetherWith(
                    fadeOut(motion.tween(MotionTokens.Fast, easing = MotionEase.In)),
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
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val accent = BiliPinkStrong
    val trackColor = BiliTrack
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
        animationSpec = motion.tween(MotionTokens.Fast),
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
                inactiveTrackColor = BiliTrack,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            thumb = {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .graphicsLayer {
                                scaleX = dragScale
                                scaleY = dragScale
                            }
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
                            .height(2.dp),
                    ) {
                        drawRect(
                            color = trackColor,
                            size = size,
                        )
                        val active = size.width * sliderState.value.coerceIn(0f, 1f)
                        if (active > 0.5f) {
                            drawRect(
                                color = accent,
                                size = Size(active, size.height),
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
                color = if (state.isBuffering) BiliMuted else BiliMuted,
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
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val accent = BiliPink
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
                    fadeIn(motion.tween(MotionTokens.Fast)).togetherWith(
                        fadeOut(motion.tween(MotionTokens.Fast)),
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
    onClick: () -> Unit,
) {
    val motion = LocalMotion.current
    val accent = BiliPink
    val interactionSource = remember { MutableInteractionSource() }
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = accent,
            contentColor = BiliOnPink,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(64.dp)
                .pressScale(interactionSource, pressedScale = 0.94f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        fadeIn(motion.tween(MotionTokens.Fast)).togetherWith(
                            fadeOut(motion.tween(MotionTokens.Fast)),
                        )
                    },
                    label = "play-glyph",
                ) { playing ->
                    Icon(
                        painter = painterResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = BiliOnPink,
                        modifier = Modifier.size(30.dp),
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
                    color = BiliMuted,
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
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(1.dp, BiliLine),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BTR 引擎", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(8.dp))
                when {
                    engine == null -> InfoChip("解析中", BiliMuted)
                    engine.mode == "btr" -> InfoChip("BTR", BiliText2)
                    else -> InfoChip("单连接", BiliMuted)
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
                        color = BiliMuted,
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
        ProgressLine(engine.progress.toFloat(), color = BiliMuted, resetKey = resetKey)
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
                color = BiliText2,
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
                color = BiliMuted,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(engine.progress.toFloat(), color = BiliMuted, resetKey = resetKey)
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
