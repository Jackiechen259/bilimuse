package com.bilimuse.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bilimuse.app.data.BiliClient
import com.bilimuse.app.data.BiliException
import com.bilimuse.app.data.DemoData
import com.bilimuse.app.data.EngineSettings
import com.bilimuse.app.data.FULL_MAX_PAGES
import com.bilimuse.app.data.FULL_PAGE_GAP_MS
import com.bilimuse.app.data.FULL_PAGE_SIZE
import com.bilimuse.app.data.Folder
import com.bilimuse.app.data.FolderPage
import com.bilimuse.app.data.LyricsCandidate
import com.bilimuse.app.data.LyricsRepository
import com.bilimuse.app.data.LyricsResult
import com.bilimuse.app.data.LyricsSourceOption
import com.bilimuse.app.data.NotLoggedInException
import com.bilimuse.app.data.QrInfo
import com.bilimuse.app.data.QrPollResult
import com.bilimuse.app.data.StreamRegistry
import com.bilimuse.app.data.TrackItem
import com.bilimuse.app.data.UserInfo
import com.bilimuse.app.data.loadAllFolderTracks
import com.bilimuse.app.data.update.UpdateCheckResult
import com.bilimuse.app.data.update.UpdateStatus
import com.bilimuse.app.data.update.UpdateUiState
import com.bilimuse.app.player.PlayQueueUiState
import com.bilimuse.app.player.PlayerController
import com.bilimuse.app.player.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val loading: Boolean = true,
    val user: UserInfo = UserInfo(),
    val demo: Boolean = false,
    /** 当前显示在收藏夹条里的收藏夹（已按本机设置过滤）。 */
    val folders: List<Folder> = emptyList(),
    /** 账号下的全部收藏夹，收藏夹管理页用它来解释每一项的显示开关。 */
    val allFolders: List<Folder> = emptyList(),
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

/**
 * 「随机播放整个收藏夹」的后台补全进度。
 * 列表只加载用户滚到的页，随机池需要把剩下的页补全，UI 用这个状态显示范围/进度。
 */
data class ShufflePoolState(
    val preparing: Boolean = false,
    val loaded: Int = 0,
    val total: Int = 0,
    val ready: Boolean = false,
    val scope: String = "整个收藏夹",
    val error: String? = null,
)

/**
 * 歌词视图状态。
 *
 * 只有 [open] 为 true 时才允许发起解析；[result] 为空且 [loading] 为 false 时，界面按
 * [LyricsResult.reason]（`not_found` / `disabled` / ...）显示对应空状态。
 * [error] 只放「解析本身失败」的可展示文案，例如没有正在播放的曲目。
 */
data class LyricsUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val result: LyricsResult? = null,
    val error: String = "",
)

/** 播放队列对应的收藏夹视图（收藏夹 + 搜索词），用于判断随机池是否同一批内容。 */
private data class QueueSource(
    val folderId: Long,
    val title: String,
    val keyword: String,
    val totalHint: Int,
    val demo: Boolean,
) {
    val key: String = if (demo) "demo:${keyword.lowercase()}" else "$folderId:${keyword.lowercase()}"
    val scope: String = if (keyword.isEmpty()) "整个收藏夹" else "全部搜索结果"
}

/**
 * 队列面板需要的来源快照。
 * 用户切到别的收藏夹后，列表状态已经不再描述正在播放的队列，所以总数、分页
 * 和「是否已补全整个收藏夹」要在开始播放时记下来。
 */
private data class QueueMeta(
    val key: String = "",
    val scope: String = "整个收藏夹",
    val totalHint: Int = 0,
    /** 随机播放已经把这个来源的全部曲目补进队列，顺序播放也不再需要「加载下一页」。 */
    val full: Boolean = false,
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

    /**
     * 歌词数据层：与播放链路完全独立。只有歌词视图打开时才调用 [LyricsRepository.resolve]，
     * 断网 / 超时 / 接口变化都只退化成空状态，不影响播放。
     */
    private val lyricsRepository = LyricsRepository(
        app,
        bili,
        { settingsStore.settings.value },
    )

    private val _lyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = _lyrics.asStateFlow()

    /** 当前这一轮歌词解析；关闭视图或切歌时取消，避免迟到结果覆盖新曲目。 */
    private var lyricsJob: Job? = null

    /** 自增 token：和 bvid 一起校验，迟到的解析结果直接丢弃。 */
    private var lyricsToken = 0L

    private val _library = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _shufflePool = MutableStateFlow(ShufflePoolState())
    val shufflePool: StateFlow<ShufflePoolState> = _shufflePool.asStateFlow()

    private val _queueMeta = MutableStateFlow(QueueMeta())

    /**
     * 播放队列面板状态：实际队列来自 MediaController，来源 / 分页信息来自本机快照。
     * 只有仍停留在同一个收藏夹视图时才允许「加载下一页」，避免把另一个收藏夹
     * 的内容补进正在播放的队列。
     */
    val queue: StateFlow<PlayQueueUiState> = combine(
        player.queue,
        _library,
        _shufflePool,
        _queueMeta,
    ) { queueState, libraryState, poolState, meta ->
        val viewKey = if (libraryState.demo) {
            "demo:${libraryState.search.trim().lowercase()}"
        } else {
            "${libraryState.selectedFolderId}:${libraryState.search.trim().lowercase()}"
        }
        val sameView = meta.key.isNotEmpty() && meta.key == viewKey
        PlayQueueUiState(
            items = queueState.items,
            currentIndex = queueState.currentIndex,
            shuffle = queueState.shuffle,
            scope = meta.scope,
            total = if (queueState.shuffle) {
                queueState.items.size
            } else {
                maxOf(meta.totalHint, queueState.items.size)
            },
            hasMore = !queueState.shuffle && sameView && !meta.full && libraryState.hasMore,
            loadingMore = sameView && libraryState.loadingMore,
            preparing = queueState.shuffle && poolState.preparing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PlayQueueUiState(),
    )

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

    /** 当前播放队列来自哪个收藏夹视图；开启随机时按它补全整个收藏夹。 */
    private var queueSource: QueueSource? = null
    private var fullLoadJob: Job? = null
    private var fullLoadKey: String? = null
    private val fullTracksCache = LinkedHashMap<String, List<TrackItem>>()

    init {
        viewModelScope.launch {
            player.errors.collect { _messages.tryEmit(it) }
        }
        // 通知栏 / 蓝牙等外部控制也能切换随机模式，所以统一监听播放器状态。
        viewModelScope.launch {
            player.state
                .map { it.shuffle }
                .distinctUntilChanged()
                .collect { enabled -> onShuffleChanged(enabled) }
        }
        refreshState()
        viewModelScope.launch {
            // 让收藏夹先出首屏，再在后台静默问一次 GitHub。
            delay(2_500)
            autoCheckForUpdates()
        }
        // 歌词视图打开时才跟随切歌重载；只监听 bvid，500ms 的进度 tick 不参与。
        viewModelScope.launch {
            player.state
                .map { it.bvid }
                .distinctUntilChanged()
                .collect { bvid ->
                    if (!_lyrics.value.open) return@collect
                    if (bvid.isEmpty()) {
                        lyricsJob?.cancel()
                        lyricsJob = null
                        lyricsToken += 1
                        _lyrics.update {
                            it.copy(loading = false, result = null, error = "没有正在播放的曲目")
                        }
                    } else {
                        startLyricsLoad(refresh = false)
                    }
                }
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
                    it.copy(
                        folders = emptyList(),
                        allFolders = emptyList(),
                        tracks = emptyList(),
                        selectedFolderId = null,
                    )
                }
            }
        }
    }

    suspend fun generateQr(): Result<QrInfo> = catching { bili.qrGenerate() }

    suspend fun pollQr(key: String): Result<QrPollResult> = catching { bili.qrPoll(key) }

    fun onLoginSuccess() {
        registry.setDemoMode(false)
        player.stopAndClear()
        resetQueuePreparation()
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
            resetQueuePreparation()
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
        resetQueuePreparation()
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
            resetQueuePreparation()
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
        resetQueuePreparation()
        _library.update {
            it.copy(
                demo = false,
                folders = emptyList(),
                allFolders = emptyList(),
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
            applyFolderVisibility(listOf(DemoData.folder))
            return
        }
        try {
            applyFolderVisibility(bili.folders())
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            _library.update {
                it.copy(
                    loading = false,
                    user = UserInfo(),
                    folders = emptyList(),
                    allFolders = emptyList(),
                )
            }
            _needLogin.tryEmit(Unit)
        } catch (e: Exception) {
            _library.update { it.copy(loading = false, error = e.message ?: "收藏夹加载失败") }
        }
    }

    /**
     * 按本机保存的隐藏列表过滤收藏夹。
     * 当前收藏夹被隐藏时自动切到第一个可见项；全部隐藏则清空列表并停在空状态。
     */
    private fun applyFolderVisibility(allFolders: List<Folder>) {
        val hidden = settingsStore.settings.value.hiddenFolderIds
        val visible = allFolders.filterNot { it.id in hidden }
        val current = visible.firstOrNull { it.id == _library.value.selectedFolderId }

        if (visible.isEmpty()) {
            tracksJob?.cancel()
            searchJob?.cancel()
            _library.update {
                it.copy(
                    allFolders = allFolders,
                    folders = emptyList(),
                    selectedFolderId = null,
                    folderTitle = if (allFolders.isEmpty()) "收藏夹" else "选择一个收藏夹",
                    tracks = emptyList(),
                    total = 0,
                    page = 1,
                    hasMore = false,
                    loading = false,
                    loadingTracks = false,
                    loadingMore = false,
                    search = "",
                    error = null,
                )
            }
            return
        }

        _library.update {
            it.copy(
                allFolders = allFolders,
                folders = visible,
                loading = false,
                error = null,
            )
        }
        if (current != null) return
        val next = visible.first()
        selectFolder(next.id, next.title)
    }

    fun setFolderHidden(folderId: Long, hidden: Boolean) {
        val settings = settingsStore.settings.value
        if ((folderId in settings.hiddenFolderIds) == hidden) return
        settingsStore.setFolderHidden(folderId, hidden)
        applyFolderVisibility(_library.value.allFolders)
        if (hidden && _library.value.folders.isEmpty() && _library.value.allFolders.isNotEmpty()) {
            _messages.tryEmit("所有收藏夹已隐藏，可在收藏夹管理中恢复")
        }
    }

    fun showAllFolders() {
        if (settingsStore.settings.value.hiddenFolderIds.isEmpty()) return
        settingsStore.showAllFolders()
        applyFolderVisibility(_library.value.allFolders)
    }

    /** 一键全不选：隐藏全部收藏夹，书库会变成空列表（可再一键全选恢复）。 */
    fun hideAllFolders() {
        val folders = _library.value.allFolders
        if (folders.isEmpty()) return
        val ids = folders.map { it.id }.toSet()
        if (settingsStore.settings.value.hiddenFolderIds.containsAll(ids)) return
        settingsStore.hideAllFolders(ids)
        applyFolderVisibility(folders)
        _messages.tryEmit("已隐藏全部收藏夹，可在收藏夹管理中一键恢复")
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
        // 列表往下翻页时，正在播放的同一收藏夹队列也跟着变长；桌面版队列也是
        // 直接读取列表，所以这里保持三端一致。已存在的 bvid 会被自动跳过。
        if (append) {
            val key = if (registry.demoMode) {
                "demo:${keyword.lowercase()}"
            } else {
                "$folderId:${keyword.lowercase()}"
            }
            if (queueSource?.key == key && player.queueSize() > 0) {
                player.appendToQueue(result.items)
            }
        }
    }

    private suspend fun fetchTracks(
        folderId: Long,
        title: String,
        page: Int,
        keyword: String,
        size: Int = PAGE_SIZE,
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
            size = size,
            keyword = keyword,
            title = title,
        )
    }

    // ------------------------------------------------------------------ 随机池（整个收藏夹）

    /** 当前列表对应的收藏夹视图；播放时把它记下来，随机池按同一个视图补全。 */
    private fun currentQueueSource(): QueueSource {
        val state = _library.value
        val keyword = state.search.trim()
        val demo = registry.demoMode
        val folder = state.folders.firstOrNull { it.id == state.selectedFolderId }
        return QueueSource(
            folderId = state.selectedFolderId ?: -1L,
            title = state.folderTitle,
            keyword = keyword,
            totalHint = if (!demo && keyword.isEmpty()) folder?.count ?: 0 else 0,
            demo = demo,
        )
    }

    private fun onShuffleChanged(enabled: Boolean) {
        if (!enabled) {
            fullLoadJob?.cancel()
            fullLoadJob = null
            fullLoadKey = null
            _shufflePool.update { it.copy(preparing = false) }
            return
        }
        // 没有队列时没有「随机范围」可言；等真正开始播放时再补全。
        if (player.queueSize() == 0) return
        // 队列来源在 play() 里记录；ViewModel 重建后没有来源时不贸然把别的收藏夹补进来。
        val source = queueSource ?: return
        if (source.folderId <= 0L && !source.demo) return
        prepareFullQueue(source)
    }

    /**
     * 把播放队列补成整个收藏夹（或全部搜索结果）。
     *
     * 列表分页只加载用户滚到的位置，ExoPlayer 的随机只能在队列内打乱，所以这里
     * 后台把剩余页全部拉下来，再分批追加进当前队列。队列和当前播放项都不会被重置。
     */
    private fun prepareFullQueue(source: QueueSource) {
        fullTracksCache[source.key]?.let { cached ->
            applyFullQueue(source, cached, announce = false)
            return
        }
        if (fullLoadJob?.isActive == true && fullLoadKey == source.key) return

        val state = _library.value
        val view = currentQueueSource()
        if (source.key == view.key && !state.hasMore && state.tracks.isNotEmpty()) {
            // 当前列表已经全部加载完（例如演示模式或不足一页），不用再请求。
            cacheFullTracks(source.key, state.tracks)
            applyFullQueue(source, state.tracks, announce = false)
            return
        }

        fullLoadJob?.cancel()
        fullLoadKey = source.key
        _shufflePool.value = ShufflePoolState(
            preparing = true,
            total = source.totalHint,
            scope = source.scope,
        )
        fullLoadJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                val tracks = loadAllFolderTracks(
                    pageSize = FULL_PAGE_SIZE,
                    totalHint = source.totalHint,
                    maxPages = FULL_MAX_PAGES,
                    delayBetweenPagesMs = FULL_PAGE_GAP_MS,
                    fetch = { page, size -> fetchFullPage(source, page, size) },
                    onPage = { loaded, total ->
                        _shufflePool.update {
                            it.copy(
                                preparing = true,
                                loaded = loaded,
                                total = total,
                                scope = source.scope,
                            )
                        }
                    },
                )
                if (tracks.isEmpty()) {
                    throw BiliException(-1, "收藏夹里没有可播放的内容")
                }
                cacheFullTracks(source.key, tracks)
                applyFullQueue(source, tracks, announce = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotLoggedInException) {
                _shufflePool.update {
                    it.copy(
                        preparing = false,
                        loaded = player.queueSize(),
                        error = "登录已失效，请重新扫码",
                    )
                }
                _messages.tryEmit("登录已失效，请重新扫码")
                _needLogin.tryEmit(Unit)
            } catch (e: Exception) {
                _shufflePool.update {
                    it.copy(
                        preparing = false,
                        loaded = player.queueSize(),
                        error = e.message ?: "加载失败",
                    )
                }
                _messages.tryEmit("加载整个收藏夹失败：${e.message ?: "网络错误"}")
            } finally {
                if (fullLoadJob === self) {
                    fullLoadJob = null
                    fullLoadKey = null
                }
            }
        }
    }

    private suspend fun fetchFullPage(source: QueueSource, page: Int, size: Int): FolderPage {
        var lastError: Exception? = null
        repeat(FULL_PAGE_ATTEMPTS) { attempt ->
            try {
                val result = fetchTracks(source.folderId, source.title, page, source.keyword, size)
                // 搜索接口返回的 info.media_count 是整个收藏夹的数量，不能当搜索结果总数。
                return if (source.keyword.isEmpty()) result else result.copy(total = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotLoggedInException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < FULL_PAGE_ATTEMPTS - 1) delay(600L * (attempt + 1))
            }
        }
        throw lastError ?: BiliException(-1, "加载失败")
    }

    private fun applyFullQueue(source: QueueSource, tracks: List<TrackItem>, announce: Boolean) {
        if (queueSource?.key != source.key) return
        _shufflePool.value = ShufflePoolState(
            ready = true,
            loaded = tracks.size,
            scope = source.scope,
        )
        _queueMeta.update {
            it.copy(totalHint = maxOf(it.totalHint, tracks.size), full = true)
        }
        val added = player.appendToQueue(tracks)
        if (announce || added > 0) {
            _messages.tryEmit(
                if (source.keyword.isEmpty()) {
                    "随机播放已覆盖整个收藏夹（${tracks.size} 首）"
                } else {
                    "随机播放已覆盖全部搜索结果（${tracks.size} 条）"
                },
            )
        }
    }

    private fun cacheFullTracks(key: String, tracks: List<TrackItem>) {
        fullTracksCache.remove(key)
        fullTracksCache[key] = tracks
        while (fullTracksCache.size > MAX_CACHED_FOLDERS) {
            val oldest = fullTracksCache.keys.firstOrNull() ?: break
            fullTracksCache.remove(oldest)
        }
    }

    /** 退出登录 / 切换演示模式时清空随机池，避免把上一个账号的收藏夹带进来。 */
    private fun resetQueuePreparation() {
        fullLoadJob?.cancel()
        fullLoadJob = null
        fullLoadKey = null
        fullTracksCache.clear()
        queueSource = null
        _shufflePool.value = ShufflePoolState()
        _queueMeta.value = QueueMeta()
    }

    // ------------------------------------------------------------------ 播放

    fun play(index: Int) {
        val tracks = _library.value.tracks
        if (index !in tracks.indices) return
        val track = tracks[index]
        val source = currentQueueSource()
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _library.update { it.copy(resolvingBvid = track.bvid) }
            try {
                registry.resolve(track.bvid, track.title)
                _library.update { it.copy(resolvingBvid = null) }
                queueSource = source
                _queueMeta.value = QueueMeta(
                    key = source.key,
                    scope = source.scope,
                    totalHint = if (source.keyword.isEmpty()) {
                        maxOf(tracks.size, _library.value.total)
                    } else {
                        tracks.size
                    },
                )
                player.playQueue(tracks, index)
                if (player.isShuffleEnabled()) prepareFullQueue(source)
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

    /** 播放队列面板里点某一首；点当前曲目只在暂停时恢复播放，不从头重来。 */
    fun playQueueItem(index: Int) {
        val queueState = queue.value
        if (index !in queueState.items.indices) return
        if (index == queueState.currentIndex) {
            if (!playerState.value.isPlaying) player.togglePlay()
            return
        }
        player.playQueueItem(index)
    }

    /**
     * 队列面板的「加载下一页」：只在列表仍停在同一个收藏夹视图时可用。
     * 新页会同时追加到列表和正在播放的队列（见 loadPage）。
     */
    fun loadMoreQueue() {
        val queueState = queue.value
        if (!queueState.canLoadMore || queueState.loadingMore) return
        loadMore()
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun setRepeatMode(mode: Int) = player.setRepeatMode(mode)

    fun setShuffle(enabled: Boolean) {
        player.setShuffle(enabled)
        onShuffleChanged(enabled)
    }

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

    fun setShowBtrDetails(enabled: Boolean) {
        settingsStore.setShowBtrDetails(enabled)
    }

    fun setMotionEnabled(enabled: Boolean) {
        settingsStore.setMotionEnabled(enabled)
    }

    /** 在线匹配歌词（LRCLIB）开关；改动后若歌词正开着，按新设置重新解析一次。 */
    fun setLyricsOnline(enabled: Boolean) {
        if (settingsStore.settings.value.lyricsOnline == enabled) return
        settingsStore.setLyricsOnline(enabled)
        if (_lyrics.value.open) startLyricsLoad(refresh = true)
    }

    /** 读取 B 站字幕开关；同样只影响之后的歌词解析，不碰播放链路。 */
    fun setLyricsBili(enabled: Boolean) {
        if (settingsStore.settings.value.lyricsBili == enabled) return
        settingsStore.setLyricsBili(enabled)
        if (_lyrics.value.open) startLyricsLoad(refresh = true)
    }

    // ------------------------------------------------------------------ 歌词

    /** 顶栏「词」按钮：打开 / 收起歌词视图。 */
    fun toggleLyrics() {
        if (_lyrics.value.open) closeLyrics() else openLyrics()
    }

    /** 打开歌词视图后才发起解析，未打开时一个歌词请求都不发。 */
    fun openLyrics() {
        if (_lyrics.value.open) return
        _lyrics.update { it.copy(open = true) }
        startLyricsLoad(refresh = false)
    }

    fun closeLyrics() {
        lyricsJob?.cancel()
        lyricsJob = null
        lyricsToken += 1
        _lyrics.update { it.copy(open = false, loading = false, error = "") }
    }

    /** 「重试」：绕过抓取缓存重新解析（只清缓存，不动本地 LRC 与偏移偏好）。 */
    fun reloadLyrics() {
        if (!_lyrics.value.open) return
        startLyricsLoad(refresh = true)
    }

    /**
     * 解析当前曲目的歌词。
     *
     * [refresh] 为 true 时绕过抓取缓存（重试按钮 / 修改歌词来源设置后使用）。
     * 迟到结果按 bvid + 自增 token 丢弃；切歌或关闭视图时取消进行中的协程。
     */
    private fun startLyricsLoad(refresh: Boolean) {
        val snapshot = playerState.value
        val bvid = snapshot.bvid
        lyricsJob?.cancel()
        lyricsJob = null
        val token = ++lyricsToken
        if (bvid.isEmpty()) {
            _lyrics.update { it.copy(loading = false, result = null, error = "没有正在播放的曲目") }
            return
        }
        _lyrics.update { it.copy(loading = true, result = null, error = "") }
        lyricsJob = viewModelScope.launch {
            val result = try {
                lyricsRepository.resolve(
                    bvid = bvid,
                    title = snapshot.title,
                    upper = snapshot.upper,
                    durationSec = (snapshot.durationMs / 1000L).toInt(),
                    refresh = refresh,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // 只接受当前这首歌、当前这一轮请求的结果；切歌 / 关闭视图后的迟到回包直接丢弃。
            if (token != lyricsToken || !_lyrics.value.open || playerState.value.bvid != bvid) {
                return@launch
            }
            if (result == null) {
                _lyrics.update {
                    it.copy(loading = false, result = null, error = "歌词解析失败，请稍后重试")
                }
            } else {
                _lyrics.update { it.copy(loading = false, result = result, error = "") }
            }
        }
    }

    /** 换源菜单：读取各来源可用性（只读，不写偏好）。网络失败退化成空列表。 */
    suspend fun lyricsSources(): List<LyricsSourceOption> {
        val snapshot = playerState.value
        if (snapshot.bvid.isEmpty()) return emptyList()
        return try {
            lyricsRepository.sources(
                bvid = snapshot.bvid,
                title = snapshot.title,
                upper = snapshot.upper,
                durationSec = (snapshot.durationMs / 1000L).toInt(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** LRCLIB 歌词搜索（最多 10 条）；关闭在线来源时仓库直接返回空列表。 */
    suspend fun searchLyrics(query: String): List<LyricsCandidate> {
        // 演示曲目的歌词是固定常量：不联网，也不允许被换成在线歌词。
        if (LyricsRepository.isDemoBvid(playerState.value.bvid) || registry.demoMode) {
            _messages.tryEmit("演示模式不搜索在线歌词")
            return emptyList()
        }
        val keyword = query.trim()
        if (keyword.isEmpty()) return emptyList()
        return try {
            lyricsRepository.search(keyword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 采用某条搜索结果的 LRCLIB 候选：仓库会写入 `pinSource=lrclib` 并锁定该 id，
     * 之后这首歌（包括切歌回来）都会优先用这条歌词。
     */
    fun useLyricsCandidate(candidate: LyricsCandidate) {
        val snapshot = playerState.value
        val bvid = snapshot.bvid
        if (bvid.isEmpty()) return
        val token = beginLyricsLoad()
        lyricsJob = viewModelScope.launch {
            val result = try {
                lyricsRepository.useCandidate(bvid, candidate.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            finishLyricsLoad(token, bvid, result)
        }
    }

    /**
     * 换源：写 `pinSource` 偏好后强制重解析（必须绕过抓取缓存，缓存命中会排在 pin 之前）。
     *
     * - `lrclib` 有候选 id 时走 [LyricsRepository.useCandidate]（内部会写 pin 并缓存）；
     * - `bili` 用 [LyricsRepository.setPin] 锁来源（人工优先、AI 兜底）；
     * - `local` / `demo` 也写 pin；
     * - 其它（工具行的「自动」项）传 `null` 回到自动阶梯。
     */
    fun selectLyricsSource(option: LyricsSourceOption) {
        if (!option.available) return
        val snapshot = playerState.value
        val bvid = snapshot.bvid
        if (bvid.isEmpty()) return
        val token = beginLyricsLoad()
        lyricsJob = viewModelScope.launch {
            val result = try {
                when (option.source) {
                    LyricsRepository.SOURCE_LRCLIB -> {
                        val id = option.matched?.id
                        if (id != null) {
                            lyricsRepository.useCandidate(bvid, id)
                        } else {
                            lyricsRepository.setPin(bvid, null)
                            resolveLyrics(snapshot, refresh = true)
                        }
                    }
                    LyricsRepository.SOURCE_BILI,
                    LyricsRepository.SOURCE_LOCAL,
                    LyricsRepository.SOURCE_DEMO,
                    -> {
                        lyricsRepository.setPin(bvid, option.source)
                        resolveLyrics(snapshot, refresh = true)
                    }
                    else -> {
                        lyricsRepository.setPin(bvid, null)
                        resolveLyrics(snapshot, refresh = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            finishLyricsLoad(token, bvid, result)
            // B 站字幕只在真的锁上时才生效；没锁住（未登录 / 字幕不足 4 行）给个提示。
            if (option.source == LyricsRepository.SOURCE_BILI &&
                result?.source != LyricsRepository.SOURCE_BILI
            ) {
                _messages.tryEmit("没能切换到 B 站字幕：未登录或没有可用字幕")
            }
        }
    }

    /** 当前这一轮歌词解析的统一入口：取消旧任务、置 loading、返回 token。 */
    private fun beginLyricsLoad(): Long {
        lyricsJob?.cancel()
        lyricsJob = null
        val token = ++lyricsToken
        _lyrics.update { it.copy(loading = true, result = null, error = "") }
        return token
    }

    /** 校验 bvid + token 后再落状态；迟到结果直接丢弃。 */
    private fun finishLyricsLoad(token: Long, bvid: String, result: LyricsResult?) {
        if (token != lyricsToken || !_lyrics.value.open || playerState.value.bvid != bvid) return
        if (result == null) {
            _lyrics.update { it.copy(loading = false, result = null, error = "歌词解析失败，请稍后重试") }
        } else {
            _lyrics.update { it.copy(loading = false, result = result, error = "") }
        }
    }

    private suspend fun resolveLyrics(snapshot: PlayerUiState, refresh: Boolean): LyricsResult =
        lyricsRepository.resolve(
            bvid = snapshot.bvid,
            title = snapshot.title,
            upper = snapshot.upper,
            durationSec = (snapshot.durationMs / 1000L).toInt(),
            refresh = refresh,
        )

    /** 偏移步进（±500ms），仅同步歌词可调；范围由数据层 clamp 到 ±30s。 */
    fun setLyricsOffset(deltaMs: Long) {
        val result = _lyrics.value.result ?: return
        if (!result.synced) return
        val bvid = playerState.value.bvid
        if (bvid.isEmpty()) return
        val next = (result.offsetMs + deltaMs)
            .coerceIn(-LyricsRepository.MAX_OFFSET_MS, LyricsRepository.MAX_OFFSET_MS)
        if (next == result.offsetMs) return
        lyricsRepository.setOffset(bvid, next)
        _lyrics.update { state ->
            val current = state.result ?: return@update state
            state.copy(result = current.copy(offsetMs = next))
        }
    }

    /**
     * 导入本地 LRC。文件读取与编码回退在界面层完成，这里只负责落盘与刷新视图；
     * 失败通过 snackbar 提示，不打断播放。
     */
    fun importLyrics(text: String) {
        val bvid = playerState.value.bvid
        if (bvid.isEmpty()) {
            _messages.tryEmit("没有正在播放的曲目，无法导入歌词")
            return
        }
        viewModelScope.launch {
            val imported = try {
                lyricsRepository.importLocal(bvid, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (playerState.value.bvid != bvid) return@launch
            if (imported == null) {
                _messages.tryEmit("导入失败：LRC 为空、超过 256 KB 或无法解析")
                return@launch
            }
            lyricsJob?.cancel()
            lyricsJob = null
            lyricsToken += 1
            _lyrics.update {
                it.copy(loading = false, result = imported, error = "")
            }
            _messages.tryEmit("已导入本地歌词")
        }
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

    private companion object {
        /** 列表分页大小，和之前的 30 保持一致。 */
        const val PAGE_SIZE = 30

        /** 单页失败时的重试次数（含第一次）。 */
        const val FULL_PAGE_ATTEMPTS = 3

        /** 内存里最多缓存几个收藏夹的全量列表。 */
        const val MAX_CACHED_FOLDERS = 3
    }
}
