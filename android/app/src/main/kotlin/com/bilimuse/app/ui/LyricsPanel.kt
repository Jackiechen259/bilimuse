package com.bilimuse.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bilimuse.app.data.LyricsCandidate
import com.bilimuse.app.data.LyricsLine
import com.bilimuse.app.data.LyricsResult
import com.bilimuse.app.data.LyricsSourceOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/** 偏移步进：与 `docs/LYRICS.md` 一致，±0.5 秒。 */
private const val OFFSET_STEP_MS = 500L

/**
 * 播放页的整页同步歌词。
 *
 * 与 `docs/LYRICS.md`「安卓界面」对应：
 * - 当前行居中放大，随播放位置自动滚动；用户拖动后暂停跟随并显示「回到当前」；
 * - 点行把播放位置跳到 `t + offset`；静态歌词（`synced=false`）不跟随、不显示偏移；
 * - 加载 / 空 / 错误三态按 `reason` 给不同文案与按钮；
 * - 工具行显示来源、匹配信息、偏移、导入、换源、搜索、重试与收起。
 *
 * 进度只用来计算「当前行索引」：`positionMs` 每 500ms 变化一次，但派生状态只在行索引
 * 真正改变时才通知列表，行内的读取保证只有上下两行重组，避免整列抖动。
 */
@Composable
fun LyricsPanel(
    result: LyricsResult?,
    loading: Boolean,
    error: String,
    positionMs: Long,
    searchSeed: String,
    onSeek: (Long) -> Unit,
    onOffset: (Long) -> Unit,
    onImport: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    onLoadSources: suspend () -> List<LyricsSourceOption>,
    onSelectSource: (LyricsSourceOption) -> Unit,
    onSearch: suspend (String) -> List<LyricsCandidate>,
    onUseCandidate: (LyricsCandidate) -> Unit,
    resetKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val listState = rememberLazyListState()

    // 切歌（bvid 变化）时恢复跟随、回到列表顶部；换源 / 搜索面板也一起收起。
    var following by remember(resetKey) { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var sourcesOpen by remember(resetKey) { mutableStateOf(false) }
    var searchOpen by remember(resetKey) { mutableStateOf(false) }
    val lineCount = result?.lines?.size ?: 0

    val latestPosition by rememberUpdatedState(positionMs)
    val activeIndexState = remember(result) {
        derivedStateOf {
            val res = result
            if (res == null || !res.synced || res.lines.isEmpty()) {
                -1
            } else {
                activeLineIndex(res.lines, latestPosition - res.offsetMs)
            }
        }
    }

    // 手指拖动直接判定为「用户接管」，不依赖 isScrollInProgress 的时序。
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) following = false
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                // 程序滚动（自动居中）不算用户操作。
                if (scrolling && !programmaticScroll) following = false
            }
    }

    LaunchedEffect(resetKey, lineCount > 0) {
        if (lineCount > 0) {
            // 新曲目的歌词行就绪时拉回顶部；列表为空（加载 / 空状态）时不调用滚动。
            listState.scrollToItem(0)
        }
    }

    // 自动跟随当前行并居中；index 连续变化时 collectLatest 会取消上一次滚动。
    LaunchedEffect(result, resetKey, following) {
        val res = result
        if (!following || res == null || !res.synced || res.lines.isEmpty()) return@LaunchedEffect
        snapshotFlow { activeIndexState.value }
            .distinctUntilChanged()
            .collectLatest { index ->
                if (index < 0) return@collectLatest
                val viewport = listState.layoutInfo.viewportSize.height
                if (viewport <= 0) return@collectLatest
                // 行顶滚到视口中线：负偏移让目标行落在列表顶部以下半个视口处。
                val centeredOffset = -viewport / 2
                programmaticScroll = true
                try {
                    if (motion.enabled) {
                        listState.animateScrollToItem(index, centeredOffset)
                    } else {
                        listState.scrollToItem(index, centeredOffset)
                    }
                } catch (e: CancellationException) {
                    // 行索引连续变化 / 用户拖动会取消上一次动画，属于正常路径。
                } finally {
                    programmaticScroll = false
                }
            }
    }

    Column(modifier) {
        LyricsToolbar(
            result = result,
            loading = loading,
            onOffset = onOffset,
            onImport = onImport,
            onReload = onReload,
            onOpenSources = { sourcesOpen = true },
            onOpenSearch = { searchOpen = true },
            onClose = onClose,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                loading -> LyricsLoading(Modifier.fillMaxSize())
                error.isNotBlank() -> LyricsEmpty(
                    title = error,
                    hint = "请稍后重试，或导入本地 LRC 文件",
                    onImport = onImport,
                    onReload = onReload,
                    modifier = Modifier.fillMaxSize(),
                )
                result == null -> LyricsEmpty(
                    title = "还没有歌词",
                    hint = "打开歌词视图时会自动匹配，也可以导入本地 LRC",
                    onImport = onImport,
                    onReload = onReload,
                    modifier = Modifier.fillMaxSize(),
                )
                !result.ok -> LyricsEmpty(
                    title = reasonTitle(result.reason),
                    hint = reasonHint(result.reason),
                    onImport = onImport,
                    onReload = onReload,
                    modifier = Modifier.fillMaxSize(),
                )
                result.lines.isEmpty() -> LyricsEmpty(
                    title = "这首歌还没有歌词",
                    hint = "可以导入本地 LRC 文件",
                    onImport = onImport,
                    onReload = onReload,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LyricsList(
                    result = result,
                    activeIndexState = activeIndexState,
                    listState = listState,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val canFollow = result?.let { it.ok && it.synced && it.lines.isNotEmpty() } == true
            if (canFollow && !following) {
                BackToCurrent(
                    onClick = { following = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }
    }

    if (sourcesOpen) {
        LyricsSourceSheet(
            loadSources = onLoadSources,
            currentSource = result?.source.orEmpty(),
            onDismiss = { sourcesOpen = false },
            onSelect = { option ->
                sourcesOpen = false
                onSelectSource(option)
            },
        )
    }

    if (searchOpen) {
        LyricsSearchDialog(
            initialQuery = searchSeed,
            search = onSearch,
            onDismiss = { searchOpen = false },
            onSelect = { candidate ->
                searchOpen = false
                onUseCandidate(candidate)
            },
        )
    }
}

// ---------------------------------------------------------------- 工具行

@Composable
private fun LyricsToolbar(
    result: LyricsResult?,
    loading: Boolean,
    onOffset: (Long) -> Unit,
    onImport: () -> Unit,
    onReload: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val ok = result?.ok == true
    val synced = ok && result?.synced == true
    val pickable = result != null && !loading
    val offsetMs = result?.offsetMs ?: 0L
    val sourceLabel = result?.takeIf { it.ok }?.sourceLabel.orEmpty()
    val matchedText = result?.takeIf { it.ok }?.meta?.matched?.let { matched ->
        buildString {
            append(matched.track)
            if (matched.artist.isNotBlank()) {
                append(" · ")
                append(matched.artist)
            }
        }
    }.orEmpty()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                !ok -> Text("歌词", style = MaterialTheme.typography.titleSmall)
                sourceLabel.isBlank() -> InfoChip("歌词", BiliPink)
                else -> InfoChip(sourceLabel, BiliPink)
            }
            if (!synced && ok) {
                Spacer(Modifier.width(6.dp))
                InfoChip("静态", BiliMuted)
            }
            if (matchedText.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = matchedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            MotionIconButton(
                onClick = onClose,
                contentDescription = "收起歌词",
                tint = BiliMuted,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = BiliMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOpenSources,
                enabled = pickable,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "换源",
                    fontSize = 12.sp,
                    color = if (pickable) BiliText else BiliMuted.copy(alpha = 0.5f),
                )
            }
            TextButton(
                onClick = onOpenSearch,
                enabled = pickable,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "搜索",
                    fontSize = 12.sp,
                    color = if (pickable) BiliText else BiliMuted.copy(alpha = 0.5f),
                )
            }
            TextButton(
                onClick = onImport,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text("导入", color = BiliPink, fontSize = 12.sp)
            }
            TextButton(
                onClick = onReload,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text("重试", color = BiliMuted, fontSize = 12.sp)
            }
        }
        if (synced) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onOffset(-OFFSET_STEP_MS) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text("-0.5s", color = BiliMuted, fontSize = 12.sp)
                }
                Text(
                    text = formatOffset(offsetMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliPink,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                TextButton(
                    onClick = { onOffset(-offsetMs) },
                    enabled = offsetMs != 0L,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "复位",
                        fontSize = 12.sp,
                        color = if (offsetMs != 0L) BiliText else BiliMuted.copy(alpha = 0.5f),
                    )
                }
                TextButton(
                    onClick = { onOffset(OFFSET_STEP_MS) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text("+0.5s", color = BiliMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 换源 / 搜索

/** 「换源」面板里的自动项：清掉 pin，回到仓库的来源阶梯。 */
private const val SOURCE_AUTO = "auto"

/**
 * 换源面板：打开时现拉一次 `sources()`（只读、不写偏好），点可用项后由 ViewModel 写 pin
 * 并重解析；不可用项置灰并给出原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSourceSheet(
    loadSources: suspend () -> List<LyricsSourceOption>,
    currentSource: String,
    onDismiss: () -> Unit,
    onSelect: (LyricsSourceOption) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember { mutableStateOf(true) }
    var sources by remember { mutableStateOf<List<LyricsSourceOption>>(emptyList()) }
    LaunchedEffect(Unit) {
        sources = loadSources()
        loading = false
    }
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
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("换源", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "当前：${sourceLabel(currentSource)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BiliPink,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在检查各来源…",
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliMuted,
                    )
                }
            } else {
                SourceOptionRow(
                    label = "自动匹配",
                    description = "按本地 → 人工字幕 → LRCLIB → AI 字幕的顺序",
                    available = true,
                    current = currentSource.isBlank(),
                    onClick = {
                        onSelect(LyricsSourceOption(source = SOURCE_AUTO, available = true, label = "自动匹配"))
                    },
                )
                sources.forEach { option ->
                    SourceOptionRow(
                        label = option.label,
                        description = sourceOptionDescription(option),
                        available = option.available,
                        current = currentSource.isNotBlank() && option.source == currentSource,
                        onClick = { onSelect(option) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = BiliLine)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = BiliMuted)
                }
            }
        }
    }
}

@Composable
private fun SourceOptionRow(
    label: String,
    description: String,
    available: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = available,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .pressScale(interactionSource, pressedScale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (available) BiliText else BiliMuted.copy(alpha = 0.55f),
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (current) {
                    Spacer(Modifier.width(8.dp))
                    InfoChip("当前", BiliPink)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (available) BiliMuted else BiliMuted.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = when {
                current -> "已使用"
                available -> "使用"
                else -> "不可用"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (available) BiliPink else BiliMuted.copy(alpha = 0.55f),
        )
    }
}

/**
 * LRCLIB 搜索对话框：预填当前「标题 歌手」，输入防抖 400ms 后搜索；
 * 点候选后由 ViewModel 走 `useCandidate` 锁定并刷新歌词。
 */
@Composable
private fun LyricsSearchDialog(
    initialQuery: String,
    search: suspend (String) -> List<LyricsCandidate>,
    onDismiss: () -> Unit,
    onSelect: (LyricsCandidate) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<LyricsCandidate>>(emptyList()) }
    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            candidates = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        candidates = search(keyword)
        searched = true
        searching = false
    }
    MotionDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BiliPanel,
            border = BorderStroke(1.dp, BiliLine),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索歌词", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    MotionIconButton(
                        onClick = onDismiss,
                        contentDescription = "关闭搜索",
                        tint = BiliMuted,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = BiliMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("歌名 歌手", color = BiliMuted) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                when {
                    searching -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = BiliPink,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在搜索…", style = MaterialTheme.typography.bodySmall, color = BiliMuted)
                    }
                    candidates.isEmpty() -> Text(
                        text = if (searched && query.isNotBlank()) {
                            "没有找到匹配的歌词，换个关键词试试"
                        } else {
                            "输入歌名或「歌名 歌手」开始搜索"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliMuted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        itemsIndexed(items = candidates) { _, candidate ->
                            SearchCandidateRow(candidate = candidate, onClick = { onSelect(candidate) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCandidateRow(candidate: LyricsCandidate, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .pressScale(interactionSource, pressedScale = 0.985f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = candidate.track.ifBlank { "未知曲目" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(candidate.artist, candidate.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (candidate.duration > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatSeconds(candidate.duration),
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
            )
        }
        Spacer(Modifier.width(8.dp))
        InfoChip(
            text = if (candidate.synced) "同步" else "静态",
            color = if (candidate.synced) BiliBlue else BiliMuted,
        )
    }
}

/** 来源 key → 展示名。 */
private fun sourceLabel(source: String): String = when (source) {
    "local" -> "本地歌词"
    "bili" -> "B 站字幕"
    "lrclib" -> "LRCLIB"
    "demo" -> "演示歌词"
    else -> "自动"
}

/** 换源面板里每个来源的副标题：可用时给命中信息，不可用时给原因。 */
private fun sourceOptionDescription(option: LyricsSourceOption): String = when (option.source) {
    "local" -> if (option.available) "已导入本地 LRC" else "没有本地 LRC 文件"
    "bili" -> if (option.available) {
        if (option.kind == "ai") "AI 转写字幕" else "人工字幕"
    } else {
        "没有可用字幕（未登录时不可用）"
    }
    "lrclib" -> if (option.available) {
        option.matched?.let { "${it.track} · ${it.artist}".trim() } ?: "已匹配到条目"
    } else {
        "没有匹配到，可在「搜索」里手动选"
    }
    "demo" -> if (option.available) "演示模式歌词" else "仅演示曲目可用"
    else -> if (option.available) "可用" else "不可用"
}

// ---------------------------------------------------------------- 歌词列表

@Composable
private fun LyricsList(
    result: LyricsResult,
    activeIndexState: State<Int>,
    listState: LazyListState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val synced = result.synced
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items = result.lines) { index, line ->
            // 在行内读取派生状态：换行时只有新旧两行会重组，避免整列抖动。
            val active = synced && activeIndexState.value == index
            LyricsLineRow(
                line = line,
                active = active,
                seekTo = if (synced) {
                    { onSeek((line.timeMs + result.offsetMs).coerceAtLeast(0L)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun LyricsLineRow(
    line: LyricsLine,
    active: Boolean,
    seekTo: (() -> Unit)?,
) {
    val motion = LocalMotion.current
    val color by animateColorAsState(
        targetValue = if (active) BiliPink else BiliMuted.copy(alpha = 0.78f),
        animationSpec = motion.tween(220),
        label = "lyrics-line-color",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier = if (seekTo != null) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = "跳转到这一句",
                onClick = seekTo,
            )
    } else {
        Modifier
    }
    Text(
        text = line.text.ifBlank { " " },
        color = color,
        fontSize = if (active) 20.sp else 15.sp,
        lineHeight = if (active) 28.sp else 22.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .pressScale(interactionSource, pressedScale = 0.98f)
            .padding(horizontal = 14.dp, vertical = if (active) 6.dp else 4.dp),
    )
}

// ---------------------------------------------------------------- 三态与浮层

@Composable
private fun LyricsLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            color = BiliPink,
            strokeWidth = 2.5.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text("正在加载歌词…", style = MaterialTheme.typography.bodySmall, color = BiliMuted)
    }
}

@Composable
private fun LyricsEmpty(
    title: String,
    hint: String,
    onImport: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "♪",
            color = BiliMuted.copy(alpha = 0.65f),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = BiliText,
            textAlign = TextAlign.Center,
        )
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onImport,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BiliLine),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("导入 LRC", color = BiliText, fontSize = 12.sp)
            }
            Button(
                onClick = onReload,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BiliPink,
                    contentColor = Color(0xFF1A0A12),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text("重试", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BackToCurrent(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = BiliPanel2,
        border = BorderStroke(1.dp, BiliLine),
        modifier = modifier,
    ) {
        Text(
            text = "回到当前",
            color = BiliPink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

// ---------------------------------------------------------------- 纯函数

/** 二分查找最后一行 `timeMs <= positionMs`（歌词可能带空白行，仍需定位）。 */
private fun activeLineIndex(lines: List<LyricsLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** 偏移展示：`+0.5s` / `−1.0s` / `0.0s`。 */
private fun formatOffset(offsetMs: Long): String {
    if (offsetMs == 0L) return "偏移 0.0s"
    val sign = if (offsetMs > 0) "+" else "−"
    val tenths = (abs(offsetMs) + 50L) / 100L
    return "偏移 $sign${tenths / 10}.${tenths % 10}s"
}

/** `none` 结果的 reason → 中文标题。 */
private fun reasonTitle(reason: String): String = when (reason) {
    LyricsResult.REASON_NOT_FOUND -> "没有找到歌词"
    LyricsResult.REASON_DISABLED -> "歌词来源都已关闭"
    LyricsResult.REASON_NOT_LOGGED_IN -> "登录后可读取 B 站字幕"
    LyricsResult.REASON_TIMEOUT -> "网络超时"
    LyricsResult.REASON_ERROR -> "歌词加载失败"
    else -> "没有找到歌词"
}

/** `none` 结果的 reason → 下一步提示。 */
private fun reasonHint(reason: String): String = when (reason) {
    LyricsResult.REASON_NOT_FOUND -> "可以导入本地 LRC，或在设置中开启「在线匹配歌词」"
    LyricsResult.REASON_DISABLED -> "在设置「歌词」里开启在线匹配或 B 站字幕"
    LyricsResult.REASON_NOT_LOGGED_IN -> "未登录时只能使用本地歌词；登录后可读取 B 站字幕"
    LyricsResult.REASON_TIMEOUT -> "网络似乎不通，请检查后重试"
    LyricsResult.REASON_ERROR -> "请稍后重试，或导入本地 LRC 文件"
    else -> ""
}
