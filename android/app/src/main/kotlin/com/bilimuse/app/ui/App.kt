package com.bilimuse.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bilimuse.app.BuildConfig
import com.bilimuse.app.MainViewModel
import com.bilimuse.app.data.update.ApkInstaller
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun BiliMuseRoot(viewModel: MainViewModel = viewModel()) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val player by viewModel.playerState.collectAsStateWithLifecycle()
    val shufflePool by viewModel.shufflePool.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val autoCheckUpdates by viewModel.autoCheckUpdates.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showWebLogin by rememberSaveable { mutableStateOf(false) }
    var webLoginPassword by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
                            actionColor = BiliPink,
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                },
                bottomBar = {
                    if (player.bvid.isNotEmpty()) {
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
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            NowPlayingScreen(
                state = player,
                shufflePool = shufflePool,
                onBack = { showPlayer = false },
                onTogglePlay = viewModel::togglePlay,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onSeek = viewModel::seekTo,
                onRepeatMode = viewModel::setRepeatMode,
                onShuffle = viewModel::setShuffle,
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
