package com.bilimuse.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bilimuse.app.data.BiliClient
import com.bilimuse.app.data.DemoData
import com.bilimuse.app.data.EngineSettings
import com.bilimuse.app.data.Folder
import com.bilimuse.app.data.FolderPage
import com.bilimuse.app.data.NotLoggedInException
import com.bilimuse.app.data.QrInfo
import com.bilimuse.app.data.QrPollResult
import com.bilimuse.app.data.StreamRegistry
import com.bilimuse.app.data.TrackItem
import com.bilimuse.app.data.UserInfo
import com.bilimuse.app.data.update.UpdateCheckResult
import com.bilimuse.app.data.update.UpdateStatus
import com.bilimuse.app.data.update.UpdateUiState
import com.bilimuse.app.player.PlayerController
import com.bilimuse.app.player.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val loading: Boolean = true,
    val user: UserInfo = UserInfo(),
    val demo: Boolean = false,
    val folders: List<Folder> = emptyList(),
    val selectedFolderId: Long? = null,
    val folderTitle: String = "",
    val tracks: List<TrackItem> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val page: Int = 1,
    val loadingTracks: Boolean = false,
    val loadingMore: Boolean = false,
    val resolvingBvid: String? = null,
    val search: String = "",
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as BiliMuseApp).container
    private val bili: BiliClient = container.bili
    private val registry: StreamRegistry = container.registry
    private val settingsStore = container.settingsStore
    private val demoCdn = container.demoCdn
    private val updater = container.updater

    private val player = PlayerController(app, registry, viewModelScope)
    val playerState: StateFlow<PlayerUiState> = player.state
    val settings: StateFlow<EngineSettings> = settingsStore.settings

    private val _library = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _needLogin = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val needLogin: SharedFlow<Unit> = _needLogin.asSharedFlow()

    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()
    val autoCheckUpdates: StateFlow<Boolean> = updater.autoCheckEnabled

    private var tracksJob: Job? = null
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var updateCheckedThisSession = false

    init {
        viewModelScope.launch {
            player.errors.collect { _messages.tryEmit(it) }
        }
        refreshState()
        viewModelScope.launch {
            // 让收藏夹先出首屏，再在后台静默问一次 GitHub。
            delay(2_500)
            autoCheckForUpdates()
        }
    }

    // ------------------------------------------------------------------ 启动 / 登录

    fun refreshState() {
        viewModelScope.launch {
            _library.update { it.copy(loading = true, error = null) }
            val user = try {
                bili.state()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("读取登录态失败：${e.message}")
                bili.session.public()
            }
            _library.update { it.copy(user = user, loading = false) }
            if (registry.demoMode) {
                loadFolders()
            } else if (user.loggedIn) {
                loadFolders()
            } else {
                _library.update {
                    it.copy(folders = emptyList(), tracks = emptyList(), selectedFolderId = null)
                }
            }
        }
    }

    suspend fun generateQr(): Result<QrInfo> = catching { bili.qrGenerate() }

    suspend fun pollQr(key: String): Result<QrPollResult> = catching { bili.qrPoll(key) }

    fun onLoginSuccess() {
        registry.setDemoMode(false)
        player.stopAndClear()
        _library.update { it.copy(demo = false) }
        _messages.tryEmit("登录成功")
        refreshState()
    }

    /**
     * 官方 WebView 登录页成功后，用拿到的 cookie 校验并落盘。
     * 由 WebLoginScreen 在协程里调用，成功后会顺带加载收藏夹。
     */
    suspend fun completeWebLogin(cookies: Map<String, String>): Result<UserInfo> {
        return try {
            val user = bili.completeLoginFromCookies(cookies)
            registry.setDemoMode(false)
            player.stopAndClear()
            _library.update {
                it.copy(
                    user = user,
                    demo = false,
                    loading = false,
                    error = null,
                )
            }
            _messages.tryEmit("登录成功")
            loadFolders()
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        tracksJob?.cancel()
        searchJob?.cancel()
        playJob?.cancel()
        bili.logout()
        registry.setDemoMode(false)
        registry.closeAll()
        player.stopAndClear()
        _library.update {
            LibraryUiState(
                loading = false,
                user = UserInfo(),
                demo = false,
                error = null,
            )
        }
        _messages.tryEmit("已退出登录")
    }

    fun enterDemo() {
        viewModelScope.launch {
            registry.setDemoMode(true)
            player.stopAndClear()
            _library.update {
                it.copy(
                    demo = true,
                    user = bili.session.public(),
                    folders = listOf(DemoData.folder),
                    selectedFolderId = null,
                    tracks = emptyList(),
                    loading = false,
                    error = null,
                )
            }
            _messages.tryEmit("演示模式：本地合成音频，每条连接限速 320 KB/s")
            loadFolders()
            try {
                withContext(Dispatchers.IO) { demoCdn.ensureAll() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("演示音频生成失败：${e.message}")
            }
        }
    }

    fun exitDemo() {
        registry.setDemoMode(false)
        player.stopAndClear()
        _library.update {
            it.copy(
                demo = false,
                folders = emptyList(),
                tracks = emptyList(),
                selectedFolderId = null,
                loading = true,
            )
        }
        refreshState()
    }

    // ------------------------------------------------------------------ 收藏夹

    private suspend fun loadFolders() {
        if (registry.demoMode) {
            _library.update {
                it.copy(
                    folders = listOf(DemoData.folder),
                    loading = false,
                    error = null,
                )
            }
            selectFolder(DemoData.folder.id, DemoData.folder.title)
            return
        }
        try {
            val folders = bili.folders()
            _library.update { it.copy(folders = folders, loading = false, error = null) }
            if (folders.isEmpty()) {
                _library.update {
                    it.copy(tracks = emptyList(), folderTitle = "收藏夹", total = 0)
                }
                return
            }
            val current = _library.value.selectedFolderId
            val folder = folders.firstOrNull { it.id == current } ?: folders.first()
            selectFolder(folder.id, folder.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            _library.update {
                it.copy(loading = false, user = UserInfo(), folders = emptyList())
            }
            _needLogin.tryEmit(Unit)
        } catch (e: Exception) {
            _library.update { it.copy(loading = false, error = e.message ?: "收藏夹加载失败") }
        }
    }

    fun selectFolder(id: Long, title: String) {
        searchJob?.cancel()
        tracksJob?.cancel()
        playJob?.cancel()
        _library.update {
            it.copy(
                selectedFolderId = id,
                folderTitle = title,
                tracks = emptyList(),
                total = 0,
                page = 1,
                hasMore = false,
                loadingTracks = true,
                loadingMore = false,
                search = "",
                error = null,
            )
        }
        tracksJob = viewModelScope.launch {
            loadPage(id, title, page = 1, keyword = "", append = false)
        }
    }

    fun onSearchChange(text: String) {
        _library.update { it.copy(search = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            val state = _library.value
            val id = state.selectedFolderId ?: return@launch
            tracksJob?.cancel()
            _library.update {
                it.copy(tracks = emptyList(), loadingTracks = true, page = 1, hasMore = false)
            }
            loadPage(id, state.folderTitle, page = 1, keyword = text.trim(), append = false)
        }
    }

    fun loadMore() {
        val state = _library.value
        val id = state.selectedFolderId ?: return
        if (!state.hasMore || state.loadingTracks || state.loadingMore) return
        _library.update { it.copy(loadingMore = true) }
        tracksJob = viewModelScope.launch {
            loadPage(
                folderId = id,
                title = state.folderTitle,
                page = state.page + 1,
                keyword = state.search.trim(),
                append = true,
            )
        }
    }

    fun retry() {
        val state = _library.value
        if (state.folders.isEmpty()) {
            refreshState()
        } else {
            val folder = state.folders.firstOrNull { it.id == state.selectedFolderId }
            if (folder != null) selectFolder(folder.id, folder.title) else refreshState()
        }
    }

    private suspend fun loadPage(
        folderId: Long,
        title: String,
        page: Int,
        keyword: String,
        append: Boolean,
    ) {
        val result = try {
            fetchTracks(folderId, title, page, keyword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            _library.update {
                it.copy(
                    loadingTracks = false,
                    loadingMore = false,
                    user = UserInfo(),
                    error = "登录已失效，请重新扫码",
                )
            }
            _needLogin.tryEmit(Unit)
            return
        } catch (e: Exception) {
            _library.update {
                it.copy(
                    loadingTracks = false,
                    loadingMore = false,
                    error = e.message ?: "加载失败",
                )
            }
            return
        }
        _library.update { state ->
            val items = if (append) state.tracks + result.items else result.items
            state.copy(
                tracks = items.distinctBy { it.bvid },
                total = if (result.total > 0) result.total else items.size,
                hasMore = result.hasMore,
                page = page,
                loadingTracks = false,
                loadingMore = false,
                folderTitle = result.title.ifBlank { title },
                error = null,
            )
        }
    }

    private suspend fun fetchTracks(
        folderId: Long,
        title: String,
        page: Int,
        keyword: String,
    ): FolderPage {
        if (registry.demoMode) {
            val items = DemoData.trackItems().filter {
                keyword.isEmpty() || it.title.lowercase().contains(keyword.lowercase())
            }
            return FolderPage(
                title = DemoData.folder.title,
                items = items,
                hasMore = false,
                total = items.size,
            )
        }
        return bili.folderItems(
            mediaId = folderId,
            page = page,
            size = 30,
            keyword = keyword,
            title = title,
        )
    }

    // ------------------------------------------------------------------ 播放

    fun play(index: Int) {
        val tracks = _library.value.tracks
        if (index !in tracks.indices) return
        val track = tracks[index]
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _library.update { it.copy(resolvingBvid = track.bvid) }
            try {
                registry.resolve(track.bvid, track.title)
                _library.update { it.copy(resolvingBvid = null) }
                player.playQueue(tracks, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotLoggedInException) {
                _library.update {
                    it.copy(resolvingBvid = null, user = UserInfo())
                }
                _messages.tryEmit("登录已失效，请重新扫码")
                _needLogin.tryEmit(Unit)
            } catch (e: Exception) {
                _library.update { it.copy(resolvingBvid = null) }
                _messages.tryEmit(e.message ?: "解析音轨失败")
            }
        }
    }

    fun togglePlay() {
        val state = playerState.value
        if (state.bvid.isEmpty()) {
            play(0)
            return
        }
        player.togglePlay()
    }

    fun next() = player.next()

    fun previous() = player.previous()

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun setRepeatMode(mode: Int) = player.setRepeatMode(mode)

    fun setShuffle(enabled: Boolean) = player.setShuffle(enabled)

    // ------------------------------------------------------------------ 设置

    fun setBtrEnabled(enabled: Boolean) {
        val settings = settingsStore.setBtrEnabled(enabled)
        registry.applySettings(settings)
    }

    fun setConcurrency(value: Int) {
        val settings = settingsStore.setConcurrency(value)
        registry.applySettings(settings)
    }

    fun setChunkSize(bytes: Int) {
        val settings = settingsStore.setChunkSize(bytes)
        registry.applySettings(settings)
    }

    fun setPreferLossless(enabled: Boolean) {
        settingsStore.setPreferLossless(enabled)
    }

    // ------------------------------------------------------------------ 自动更新

    /** 手动检查：设置页入口。 */
    fun checkForUpdates() {
        if (updateCheckJob?.isActive == true || updateDownloadJob?.isActive == true) return
        updateCheckedThisSession = true
        updateCheckJob = viewModelScope.launch {
            _update.update { it.copy(status = UpdateStatus.CHECKING, error = null) }
            try {
                when (val result = updater.check()) {
                    is UpdateCheckResult.Available -> {
                        _update.value = UpdateUiState(
                            status = UpdateStatus.AVAILABLE,
                            update = result.update,
                            totalBytes = result.update.asset.size,
                        )
                        _messages.tryEmit("发现新版本 v${result.update.version}")
                    }
                    UpdateCheckResult.UpToDate -> {
                        _update.value = UpdateUiState(status = UpdateStatus.UP_TO_DATE)
                        _messages.tryEmit("已是最新版本 v${BuildConfig.VERSION_NAME}")
                    }
                    UpdateCheckResult.NoRelease -> {
                        _update.value = UpdateUiState(status = UpdateStatus.NO_RELEASE)
                        _messages.tryEmit("GitHub 上还没有已发布的版本")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.update {
                    it.copy(status = UpdateStatus.FAILED, error = e.message ?: "检查更新失败")
                }
                _messages.tryEmit(e.message ?: "检查更新失败")
            }
        }
    }

    /** 下载并校验 APK。下载完成后状态变为 READY，由界面调起系统安装器。 */
    fun downloadUpdate() {
        val update = _update.value.update ?: return
        if (updateDownloadJob?.isActive == true) return
        updateCheckJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            _update.update {
                it.copy(
                    status = UpdateStatus.DOWNLOADING,
                    downloadedBytes = 0L,
                    totalBytes = update.asset.size,
                    apkFile = null,
                    error = null,
                )
            }
            try {
                val file = updater.download(update) { downloaded, total ->
                    if (_update.value.status == UpdateStatus.DOWNLOADING) {
                        _update.update {
                            it.copy(downloadedBytes = downloaded, totalBytes = total)
                        }
                    }
                }
                val problem = withContext(Dispatchers.IO) { updater.validateApk(file, update) }
                if (problem != null) {
                    file.delete()
                    throw IllegalStateException(problem)
                }
                _update.update {
                    it.copy(
                        status = UpdateStatus.READY,
                        downloadedBytes = file.length(),
                        totalBytes = file.length(),
                        apkFile = file,
                        error = null,
                    )
                }
                _messages.tryEmit("更新包已下载并通过校验，点击安装")
            } catch (e: CancellationException) {
                // 只有当前这一轮下载被取消时才回退状态；快速重试时旧任务的收尾不能覆盖新状态。
                if (updateDownloadJob === self) {
                    _update.update {
                        it.copy(
                            status = UpdateStatus.AVAILABLE,
                            downloadedBytes = 0L,
                            totalBytes = update.asset.size,
                            apkFile = null,
                            error = null,
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                if (updateDownloadJob === self) {
                    _update.update {
                        it.copy(
                            status = UpdateStatus.FAILED,
                            apkFile = null,
                            error = e.message ?: "下载更新失败",
                        )
                    }
                    _messages.tryEmit(e.message ?: "下载更新失败")
                }
            }
        }
    }

    fun cancelUpdateDownload() {
        // 保留 job 引用，让协程自己的取消处理能把状态回退到 AVAILABLE；
        // 新的下载会覆盖这个引用，旧任务的收尾不会覆盖新状态。
        updateDownloadJob?.cancel()
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        updater.setAutoCheckEnabled(enabled)
    }

    private suspend fun autoCheckForUpdates() {
        if (updateCheckedThisSession || !updater.shouldAutoCheck()) return
        updateCheckedThisSession = true
        try {
            when (val result = updater.check()) {
                is UpdateCheckResult.Available -> {
                    _update.value = UpdateUiState(
                        status = UpdateStatus.AVAILABLE,
                        update = result.update,
                        totalBytes = result.update.asset.size,
                    )
                    _messages.tryEmit("发现新版本 v${result.update.version}，可在设置中更新")
                }
                UpdateCheckResult.NoRelease, UpdateCheckResult.UpToDate -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 启动时的自动检查失败不打扰用户；设置页仍可手动重试。
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
