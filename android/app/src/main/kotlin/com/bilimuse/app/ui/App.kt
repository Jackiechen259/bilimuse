package com.bilimuse.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bilimuse.app.BuildConfig
import com.bilimuse.app.MainViewModel
import com.bilimuse.app.data.update.ApkInstaller
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BiliMuseRoot(viewModel: MainViewModel = viewModel()) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val player by viewModel.playerState.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val shufflePool by viewModel.shufflePool.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val autoCheckUpdates by viewModel.autoCheckUpdates.collectAsStateWithLifecycle()

    val motion = LocalMotion.current
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showWebLogin by rememberSaveable { mutableStateOf(false) }
    var webLoginPassword by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var pendingInstallPath by rememberSaveable { mutableStateOf<String?>(null) }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingInstallPath?.let { File(it) }
        pendingInstallPath = null
        if (file == null) return@rememberLauncherForActivityResult
        if (!ApkInstaller.canInstall(context) || !ApkInstaller.install(context, file)) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "没有安装权限，无法启动安装器",
                    withDismissAction = true,
                )
            }
        }
    }

    // 导入本地 LRC：只取文本，UTF-8 解码失败退 GBK；取消选择（uri=null）不做事。
    val lyricsFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { readLyricsFile(context, uri) }
            if (text.isNullOrBlank()) {
                snackbarHostState.showSnackbar(
                    "无法读取歌词文件（需小于 256 KB 的 LRC 文本）",
                    withDismissAction = true,
                )
            } else {
                viewModel.importLyrics(text)
            }
        }
    }

    val requestInstall: (File) -> Unit = { file ->
        showUpdate = false
        if (ApkInstaller.canInstall(context)) {
            if (!ApkInstaller.install(context, file)) {
                scope.launch {
                    snackbarHostState.showSnackbar("无法启动系统安装程序", withDismissAction = true)
                }
            }
        } else {
            pendingInstallPath = file.absolutePath
            if (!ApkInstaller.openInstallPermissionSettings(context)) {
                pendingInstallPath = null
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "当前系统不允许安装未知应用，请到系统设置中手动开启",
                        withDismissAction = true,
                    )
                }
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message, withDismissAction = true)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.needLogin.collect { showLogin = true }
    }
    LaunchedEffect(player.bvid) {
        if (player.bvid.isEmpty()) showPlayer = false
    }
    // 详情页关闭后歌词视图也收起，下次进入始终从封面视图开始（与网页版一致）。
    LaunchedEffect(showPlayer) {
        if (!showPlayer) viewModel.closeLyrics()
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }

    Box(Modifier.fillMaxSize()) {
        AppBackground {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = {
                    SnackbarHost(snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = BiliPanel2,
                            contentColor = BiliText,
                            actionColor = BiliPinkText,
                            shape = RoundedCornerShape(2.dp),
                        )
                    }
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = player.bvid.isNotEmpty(),
                        enter = fadeIn(motion.tween(MotionTokens.Fast)),
                        exit = fadeOut(motion.tween(MotionTokens.Fast)),
                    ) {
                        MiniPlayer(
                            state = player,
                            onOpen = { showPlayer = true },
                            onTogglePlay = viewModel::togglePlay,
                            onNext = viewModel::next,
                        )
                    }
                },
            ) { innerPadding ->
                LibraryScreen(
                    state = library,
                    player = player,
                    onFolderClick = { viewModel.selectFolder(it.id, it.title) },
                    onSearchChange = viewModel::onSearchChange,
                    onPlay = viewModel::play,
                    onAccountClick = {
                        if (!library.user.loggedIn && !library.demo) showLogin = true
                        else showSettings = true
                    },
                    onSettingsClick = { showSettings = true },
                    onManageFolders = { showFavorites = true },
                    onLoginClick = { showLogin = true },
                    onDemoClick = { viewModel.enterDemo() },
                    onRetry = viewModel::retry,
                    onLoadMore = viewModel::loadMore,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }

        if (showWebLogin) {
            WebLoginScreen(
                viewModel = viewModel,
                initialPassword = webLoginPassword,
                onDismiss = { showWebLogin = false },
                onLoggedIn = {
                    showWebLogin = false
                    showLogin = false
                    showSettings = false
                },
            )
        }

        AnimatedVisibility(
            visible = showPlayer && player.bvid.isNotEmpty(),
            modifier = Modifier.zIndex(2f),
            // 整页只做一次淡入，避免布局位移与缩放。
            enter = fadeIn(motion.tween(MotionTokens.Base)),
            exit = fadeOut(motion.tween(MotionTokens.Fast)),
        ) {
            NowPlayingScreen(
                state = player,
                queue = queue,
                showBtrDetails = settings.showBtrDetails,
                shufflePool = shufflePool,
                lyrics = lyrics,
                onBack = {
                    // 歌词打开时先收起歌词，再按一次才退出播放页（与网页版 Esc 顺序一致）。
                    if (lyrics.open) viewModel.closeLyrics() else showPlayer = false
                },
                onTogglePlay = viewModel::togglePlay,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onSeek = viewModel::seekTo,
                onRepeatMode = viewModel::setRepeatMode,
                onShuffle = viewModel::setShuffle,
                onPlayQueueIndex = viewModel::playQueueItem,
                onLoadMoreQueue = viewModel::loadMoreQueue,
                onToggleLyrics = viewModel::toggleLyrics,
                onLyricsOffset = viewModel::setLyricsOffset,
                onImportLyrics = {
                    lyricsFileLauncher.launch(
                        arrayOf("text/plain", "application/octet-stream", "*/*"),
                    )
                },
                onReloadLyrics = viewModel::reloadLyrics,
                onCloseLyrics = viewModel::closeLyrics,
                onLoadLyricsSources = { viewModel.lyricsSources() },
                onSelectLyricsSource = viewModel::selectLyricsSource,
                onSearchLyrics = { query -> viewModel.searchLyrics(query) },
                onUseLyricsCandidate = viewModel::useLyricsCandidate,
            )
        }

        AnimatedVisibility(
            visible = showFavorites,
            modifier = Modifier.zIndex(1f),
            enter = slideInHorizontally(
                initialOffsetX = { it / 6 },
                animationSpec = motion.spring(dampingRatio = 0.88f, stiffness = 300f),
            ) + fadeIn(motion.tween(MotionTokens.Base)),
            exit = slideOutHorizontally(
                targetOffsetX = { it / 5 },
                animationSpec = motion.tween(260, easing = MotionEase.In),
            ) + fadeOut(motion.tween(200)),
        ) {
            FavoritesScreen(
                state = library,
                hiddenFolderIds = settings.hiddenFolderIds,
                onBack = { showFavorites = false },
                onFolderHidden = viewModel::setFolderHidden,
                onShowAllFolders = viewModel::showAllFolders,
                onHideAllFolders = viewModel::hideAllFolders,
                onLogin = {
                    showFavorites = false
                    showLogin = true
                },
                onRefresh = viewModel::refreshState,
            )
        }
    }

    if (showLogin) {
        LoginDialog(
            viewModel = viewModel,
            onDismiss = { showLogin = false },
            onLoggedIn = {
                showLogin = false
                viewModel.onLoginSuccess()
            },
            onDemo = {
                showLogin = false
                viewModel.enterDemo()
            },
            onWebLogin = { password ->
                showLogin = false
                webLoginPassword = password
                showWebLogin = true
            },
        )
    }

    if (showSettings) {
        SettingsSheet(
            user = library.user,
            demo = library.demo,
            settings = settings,
            version = BuildConfig.VERSION_NAME,
            update = update,
            autoCheckUpdates = autoCheckUpdates,
            onDismiss = { showSettings = false },
            onLogin = {
                showSettings = false
                showLogin = true
            },
            onLogout = {
                showSettings = false
                viewModel.logout()
            },
            onDemo = {
                showSettings = false
                viewModel.enterDemo()
            },
            onExitDemo = {
                showSettings = false
                viewModel.exitDemo()
            },
            onBtrEnabled = viewModel::setBtrEnabled,
            onConcurrency = viewModel::setConcurrency,
            onChunkSize = viewModel::setChunkSize,
            onLossless = viewModel::setPreferLossless,
            onShowBtrDetails = viewModel::setShowBtrDetails,
            theme = AppTheme.fromKey(settings.theme),
            onTheme = viewModel::setTheme,
            onMotionEnabled = viewModel::setMotionEnabled,
            onLyricsOnline = viewModel::setLyricsOnline,
            onLyricsBili = viewModel::setLyricsBili,
            onAutoCheckUpdates = viewModel::setAutoCheckUpdates,
            onCheckUpdate = viewModel::checkForUpdates,
            onOpenUpdate = {
                showSettings = false
                showUpdate = true
            },
        )
    }

    if (showUpdate && update.update != null) {
        UpdateDialog(
            state = update,
            currentVersion = BuildConfig.VERSION_NAME,
            onDismiss = { showUpdate = false },
            onDownload = viewModel::downloadUpdate,
            onCancelDownload = viewModel::cancelUpdateDownload,
            onInstall = requestInstall,
            onOpenReleasePage = { url -> ApkInstaller.openUrl(context, url) },
        )
    }
}

/** 导入歌词的上限：与 `docs/LYRICS.md` 的 256 KB 一致。 */
private const val LYRICS_IMPORT_MAX_BYTES = 256 * 1024

/**
 * 读取用户选择的歌词文件。
 *
 * 先按 UTF-8 严格解码（同时吃掉 BOM），失败回退 GBK；超过 256 KB 或内容为空返回 null。
 * 解析规则由数据层（[com.bilimuse.app.data.LyricsLrc]）负责，这里只把文本交出去。
 */
private fun readLyricsFile(context: Context, uri: Uri): String? {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(LYRICS_IMPORT_MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            if (total > LYRICS_IMPORT_MAX_BYTES) null else buffer.copyOf(total)
        }
    }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null
    return decodeLyricsText(bytes)
}

private fun decodeLyricsText(bytes: ByteArray): String {
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    }.getOrNull()
    if (utf8 != null) return utf8
    return runCatching { String(bytes, charset("GBK")) }
        .getOrElse { String(bytes, Charsets.UTF_8) }
}
