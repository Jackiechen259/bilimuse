package com.bilimuse.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bilimuse.app.LibraryUiState
import com.bilimuse.app.data.Folder
import com.bilimuse.app.data.TrackItem
import com.bilimuse.app.data.UserInfo
import com.bilimuse.app.player.PlayerUiState

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    player: PlayerUiState,
    onFolderClick: (Folder) -> Unit,
    onSearchChange: (String) -> Unit,
    onPlay: (Int) -> Unit,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageFolders: () -> Unit,
    onLoginClick: () -> Unit,
    onDemoClick: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val motion = LocalMotion.current
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchOpen) {
        if (searchOpen) focusRequester.requestFocus()
    }
    androidx.activity.compose.BackHandler(enabled = searchOpen) {
        searchOpen = false
        onSearchChange("")
    }
    LaunchedEffect(state.selectedFolderId, state.search, state.tracks.firstOrNull()?.bvid) {
        if (state.tracks.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(modifier.fillMaxSize()) {
        LibraryTopBar(
            user = state.user,
            demo = state.demo,
            searchOpen = searchOpen,
            onSearchToggle = {
                searchOpen = !searchOpen
                if (!searchOpen) onSearchChange("")
            },
            onAccountClick = onAccountClick,
            onSettingsClick = onSettingsClick,
        )

        AnimatedVisibility(
            visible = searchOpen,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = motion.spring(dampingRatio = 0.9f, stiffness = 320f),
            ) + fadeIn(motion.tween(180)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = motion.tween(220, easing = MotionEase.InOut),
            ) + fadeOut(motion.tween(140)),
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("在收藏夹内搜索…", color = BiliMuted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = BiliMuted)
                },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = state.search.isNotEmpty(),
                        enter = fadeIn(motion.tween(140)) + scaleIn(
                            initialScale = 0.7f,
                            animationSpec = motion.spring(dampingRatio = 0.6f, stiffness = 700f),
                        ),
                        exit = fadeOut(motion.tween(100)) + scaleOut(
                            targetScale = 0.7f,
                            animationSpec = motion.tween(120, easing = MotionEase.In),
                        ),
                    ) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清空", tint = BiliMuted)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BiliPink.copy(alpha = 0.6f),
                    unfocusedBorderColor = BiliLine,
                    focusedContainerColor = BiliPanel,
                    unfocusedContainerColor = BiliPanel,
                    cursorColor = BiliPink,
                ),
            )
        }

        if (state.folders.isNotEmpty()) {
            FolderChips(
                folders = state.folders,
                selectedId = state.selectedFolderId,
                onFolderClick = onFolderClick,
            )
        }

        FolderHeader(state, onManageFolders)

        Box(Modifier.weight(1f)) {
            when {
                state.loading -> LoadingBox()
                state.loadingTracks && state.tracks.isEmpty() -> LoadingList()
                state.tracks.isEmpty() -> EmptyOrError(
                    state = state,
                    onLogin = onLoginClick,
                    onDemo = onDemoClick,
                    onRetry = onRetry,
                    onManageFolders = onManageFolders,
                )
                else -> TrackList(
                    state = state,
                    player = player,
                    listState = listState,
                    onPlay = onPlay,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun LibraryTopBar(
    user: UserInfo,
    demo: Boolean,
    searchOpen: Boolean,
    onSearchToggle: () -> Unit,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val motion = LocalMotion.current
    Row(
        Modifier
            .fillMaxWidth()
            .staggerIn(index = 0, distance = 7.dp)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.staggerIn(index = 0, distance = 0.dp, scaleFrom = 0.82f)) {
            BrandLogo(38.dp)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text("BiliMuse", style = MaterialTheme.typography.titleLarge)
            Text(
                text = when {
                    demo -> "演示模式 · 无需登录"
                    !user.loggedIn -> "把收藏夹变成歌单"
                    user.vip -> "${user.uname.ifBlank { "大会员" }} · 大会员"
                    else -> user.uname.ifBlank { "已登录" }
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MotionIconButton(
            onClick = onSearchToggle,
            contentDescription = if (searchOpen) "关闭搜索" else "搜索",
            tint = if (searchOpen) BiliPink else BiliText,
        ) {
            AnimatedContent(
                targetState = searchOpen,
                transitionSpec = {
                    (fadeIn(motion.tween(160)) + scaleIn(
                        initialScale = 0.65f,
                        animationSpec = motion.spring(dampingRatio = 0.6f, stiffness = 700f),
                    )).togetherWith(
                        fadeOut(motion.tween(110)) + scaleOut(
                            targetScale = 0.65f,
                            animationSpec = motion.tween(120, easing = MotionEase.In),
                        ),
                    )
                },
                label = "search-glyph",
            ) { open ->
                Icon(
                    imageVector = if (open) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (open) BiliPink else BiliText,
                )
            }
        }
        MotionIconButton(
            onClick = onSettingsClick,
            contentDescription = "设置",
            tint = BiliText,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = BiliText)
        }
        AccountAvatar(user = user, demo = demo, onClick = onAccountClick)
    }
}

@Composable
private fun AccountAvatar(user: UserInfo, demo: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .padding(start = 2.dp, end = 4.dp)
            .size(36.dp)
            .pressScale(interactionSource, pressedScale = 0.88f)
            .clip(CircleShape)
            .background(
                if (demo) CoverFallbackBrush
                else androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF2B3242), Color(0xFF1A1F2B)),
                ),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            demo -> Text("♪", color = Color.White, fontWeight = FontWeight.Bold)
            user.face.isNotBlank() -> AsyncImage(
                model = user.face,
                contentDescription = "账号",
                modifier = Modifier.fillMaxSize(),
            )
            user.loggedIn -> Text(
                text = (user.uname.ifBlank { "B" }).take(1),
                color = BiliText,
                fontWeight = FontWeight.SemiBold,
            )
            else -> Icon(Icons.Filled.Person, contentDescription = "登录", tint = BiliMuted)
        }
    }
}

@Composable
private fun FolderChips(
    folders: List<Folder>,
    selectedId: Long?,
    onFolderClick: (Folder) -> Unit,
) {
    val motion = LocalMotion.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(folders, key = { _, folder -> folder.id }) { index, folder ->
            val selected = folder.id == selectedId
            val interactionSource = remember { MutableInteractionSource() }
            val container by animateColorAsState(
                targetValue = if (selected) BiliPink.copy(alpha = 0.16f) else BiliPanel,
                animationSpec = motion.tween(220),
                label = "chip-container",
            )
            val borderColor by animateColorAsState(
                targetValue = if (selected) BiliPink.copy(alpha = 0.5f) else BiliLine,
                animationSpec = motion.tween(220),
                label = "chip-border",
            )
            val labelColor by animateColorAsState(
                targetValue = if (selected) BiliPink else BiliText,
                animationSpec = motion.tween(220),
                label = "chip-label",
            )
            val selectedScale by animateFloatAsState(
                targetValue = if (selected) 1.025f else 1f,
                animationSpec = motion.spring(dampingRatio = 0.55f, stiffness = 520f),
                label = "chip-scale",
            )
            Surface(
                onClick = { onFolderClick(folder) },
                shape = RoundedCornerShape(50),
                color = container,
                border = BorderStroke(1.dp, borderColor),
                interactionSource = interactionSource,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .staggerIn(index = index, distance = 7.dp, key = folder.id)
                    .pressScale(interactionSource, pressedScale = 0.94f)
                    .graphicsLayer {
                        scaleX = selectedScale
                        scaleY = selectedScale
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = folder.title,
                        color = labelColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                    if (folder.count > 0) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = folder.count.toString(),
                            color = labelColor.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(state: LibraryUiState, onManageFolders: () -> Unit) {
    val motion = LocalMotion.current
    val title = state.folderTitle.ifBlank {
        if (state.demo) "演示 · 合成器浪潮" else "我的收藏夹"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .staggerIn(index = 3, distance = 8.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = title,
                transitionSpec = {
                    (fadeIn(motion.tween(260)) + slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = motion.tween(340, easing = MotionEase.OutExpo),
                    )).togetherWith(
                        fadeOut(motion.tween(140)) + slideOutVertically(
                            targetOffsetY = { -it / 4 },
                            animationSpec = motion.tween(180, easing = MotionEase.In),
                        ),
                    )
                },
                label = "folder-title",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    state.loadingTracks -> "正在加载…"
                    state.search.isNotBlank() -> "${state.tracks.size} 个搜索结果"
                    state.demo -> "本地合成音轨 · 每条连接限速 320 KB/s"
                    state.total > 0 -> "${state.total} 个视频 · 点击任意一行播放"
                    else -> "选择一个收藏夹"
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(
            visible = state.user.loggedIn || state.demo,
            enter = fadeIn(motion.tween(200)) + scaleIn(
                initialScale = 0.9f,
                animationSpec = motion.tween(260, easing = MotionEase.OutExpo),
            ),
            exit = fadeOut(motion.tween(120)),
        ) {
            TextButton(
                onClick = onManageFolders,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("管理收藏夹", color = BiliPink, fontSize = 12.sp)
            }
        }
        AnimatedVisibility(
            visible = state.demo,
            enter = fadeIn(motion.tween(220)) + scaleIn(
                initialScale = 0.86f,
                animationSpec = motion.spring(dampingRatio = 0.6f, stiffness = 600f),
            ),
            exit = fadeOut(motion.tween(120)) + scaleOut(targetScale = 0.86f),
        ) {
            InfoChip("演示模式", BiliBlue)
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BiliPink, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun LoadingList() {
    Column(Modifier.fillMaxSize()) {
        repeat(7) { index ->
            Box(Modifier.staggerIn(index = index, distance = 8.dp)) {
                SkeletonRow()
            }
        }
    }
}

@Composable
private fun EmptyOrError(
    state: LibraryUiState,
    onLogin: () -> Unit,
    onDemo: () -> Unit,
    onRetry: () -> Unit,
    onManageFolders: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .staggerIn(index = 0, distance = 10.dp)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
            if (state.error != null) {
                Text("加载失败", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BiliPink,
                        contentColor = Color(0xFF1A0A12),
                    ),
                ) { Text("重试") }
            } else if (!state.user.loggedIn && !state.demo) {
                BrandLogo(68.dp)
                Spacer(Modifier.height(18.dp))
                Text("登录 B 站，把收藏夹变成歌单", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "扫码，或使用账号密码 / 手机验证码；登录态只保存在本机",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onLogin,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BiliPink,
                        contentColor = Color(0xFF1A0A12),
                    ),
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) { Text("登录 B 站") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDemo,
                    modifier = Modifier.fillMaxWidth(0.7f),
                    border = BorderStroke(1.dp, BiliLine),
                ) { Text("先玩演示模式", color = BiliText) }
            } else if (state.allFolders.isNotEmpty() && state.folders.isEmpty()) {
                Text("所有收藏夹已隐藏", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "隐藏只影响本机列表，B 站上的收藏不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onManageFolders,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BiliPink,
                        contentColor = Color(0xFF1A0A12),
                    ),
                ) { Text("管理收藏夹") }
            } else if (state.search.isNotBlank()) {
                Text("没有找到「${state.search}」", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "试试更短的关键词",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
            } else if (state.demo) {
                Text("演示音频准备中…", style = MaterialTheme.typography.bodyMedium, color = BiliMuted)
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(color = BiliPink, strokeWidth = 2.dp)
            } else {
                Text("这里还没有内容", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "换一个收藏夹，或者去 B 站收藏一些视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )
            }
    }
}

@Composable
private fun TrackList(
    state: LibraryUiState,
    player: PlayerUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPlay: (Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    val motion = LocalMotion.current
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (state.error != null) {
            item {
                Text(
                    text = state.error,
                    color = BiliDanger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        itemsIndexed(state.tracks, key = { _, item -> item.bvid }) { index, item ->
            val isCurrent = player.bvid == item.bvid
            TrackRow(
                item = item,
                isCurrent = isCurrent,
                isPlaying = isCurrent && player.isPlaying,
                resolving = state.resolvingBvid == item.bvid,
                onClick = { onPlay(index) },
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = motion.spring(dampingRatio = 0.84f, stiffness = 420f),
                        fadeOutSpec = null,
                    )
                    .then(
                        if (index < 14) {
                            Modifier.staggerIn(
                                index = index,
                                distance = 9.dp,
                                key = item.bvid,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        if (state.hasMore) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BiliPink,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = onLoadMore) {
                            Text("加载更多", color = BiliPink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    item: TrackItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    resolving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        targetValue = if (isCurrent) BiliPink.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = motion.tween(300),
        label = "track-background",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) BiliPink else BiliText,
        animationSpec = motion.tween(260),
        label = "track-title",
    )
    val accentLine by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = motion.spring(dampingRatio = 0.7f, stiffness = 520f),
        label = "track-accent",
    )
    val coverScale by animateFloatAsState(
        targetValue = if (isCurrent) 1.055f else 1f,
        animationSpec = motion.spring(dampingRatio = 0.6f, stiffness = 460f),
        label = "track-cover",
    )
    val trailing = when {
        resolving -> 1
        isCurrent -> 2
        else -> 0
    }
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (accentLine > 0.001f) {
                    val barHeight = size.height * 0.42f * accentLine
                    drawRoundRect(
                        color = BiliPink,
                        topLeft = Offset(0f, (size.height - barHeight) / 2f),
                        size = Size(3.dp.toPx(), barHeight),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .pressScale(interactionSource, pressedScale = 0.985f)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            item.cover,
            48.dp,
            10.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = coverScale
                scaleY = coverScale
            },
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.title.ifBlank { "未命名视频" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(item.upper.ifBlank { "B站收藏夹" })
                    if (item.duration > 0) {
                        append(" · ")
                        append(formatSeconds(item.duration))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedContent(
            targetState = trailing,
            transitionSpec = {
                (fadeIn(motion.tween(180)) + scaleIn(
                    initialScale = 0.6f,
                    animationSpec = motion.spring(dampingRatio = 0.55f, stiffness = 760f),
                )).togetherWith(
                    fadeOut(motion.tween(120)) + scaleOut(
                        targetScale = 0.6f,
                        animationSpec = motion.tween(120, easing = MotionEase.In),
                    ),
                )
            },
            label = "track-trailing",
        ) { trailingState ->
            when (trailingState) {
                1 -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = BiliPink,
                    strokeWidth = 2.dp,
                )
                2 -> Box(Modifier.size(18.dp)) { PlayingBars(isPlaying) }
                else -> Spacer(Modifier.size(18.dp))
            }
        }
    }
}
