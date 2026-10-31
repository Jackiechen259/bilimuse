package com.bilimuse.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bilimuse.app.BuildConfig
import com.bilimuse.app.MainViewModel

@Composable
fun BiliMuseRoot(viewModel: MainViewModel = viewModel()) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val player by viewModel.playerState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showWebLogin by rememberSaveable { mutableStateOf(false) }
    var webLoginPassword by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }

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
        )
    }
}
