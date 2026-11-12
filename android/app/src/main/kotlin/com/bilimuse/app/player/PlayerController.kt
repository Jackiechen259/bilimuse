package com.bilimuse.app.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bilimuse.app.btr.EngineSnapshot
import com.bilimuse.app.data.StreamRegistry
import com.bilimuse.app.data.TrackInfo
import com.bilimuse.app.data.TrackItem
import com.bilimuse.app.data.missingQueueTracks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PlayerUiState(
    val connected: Boolean = false,
    val bvid: String = "",
    val title: String = "未在播放",
    val upper: String = "",
    val cover: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val info: TrackInfo? = null,
    val engine: EngineSnapshot? = null,
)

/**
 * MediaController 里的实际播放队列。
 * 只在时间线或随机模式变化时重建，避免每 500ms 的进度 tick 复制上千首的数据。
 *
 * 随机播放时 [items] 按 ExoPlayer 当前的随机顺序排列（不再是入队顺序），
 * [currentIndex] 也是这份顺序里的位置；面板点行时由 [PlayerController] 换算回时间线下标。
 */
data class PlayerQueueState(
    val items: List<TrackItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
)

/**
 * UI 与 PlaybackService 的桥：持有一个 MediaController，把播放器状态
 * 和 BTR 引擎指标汇成 StateFlow 给 Compose。
 */
class PlayerController(
    context: Context,
    private val registry: StreamRegistry,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val token = SessionToken(
        appContext,
        ComponentName(appContext, PlaybackService::class.java),
    )
    private val future = MediaController.Builder(appContext, token).buildAsync()
    private val connected = CompletableDeferred<MediaController?>()

    private var controller: MediaController? = null
    private var ticker: Job? = null

    /**
     * 当前播放队列里的 bvid，用来在补全整个收藏夹时去重。
     * 只在本进程内维护；MediaController 重连时从播放器实际队列重建。
     */
    private val queueIds = LinkedHashSet<String>()

    /**
     * 面板行下标 → 播放器时间线下标。顺序播放时两者相同；随机播放时面板按
     * ExoPlayer 的随机顺序排列，点某一行要先换算回时间线位置。
     */
    private var timelineIndexByPosition = IntArray(0)

    /** [timelineIndexByPosition] 的反查表，用来在切歌时快速定位当前行。 */
    private var positionByTimelineIndex = IntArray(0)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _queue = MutableStateFlow(PlayerQueueState())
    val queue: StateFlow<PlayerQueueState> = _queue.asStateFlow()

    /** 时间线 / 随机模式变了才需要重新遍历 MediaItem；切歌只更新下标。 */
    private var queueDirty = true

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) {
                queueDirty = true
            }
            sync()
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败，请稍后重试"
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "音频地址无效或已过期"
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "音频已过期，请重新点击播放"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "音频格式无法解析"
                else -> error.message ?: "播放失败"
            }
            _errors.tryEmit(message)
            sync()
        }
    }

    init {
        future.addListener({
            val result = runCatching { future.get() }
            val c = result.getOrNull()
            if (c == null) {
                _errors.tryEmit("播放服务连接失败：${result.exceptionOrNull()?.message.orEmpty()}")
                connected.complete(null)
                return@addListener
            }
            controller = c
            syncQueueIds(c)
            c.addListener(listener)
            _state.update { it.copy(connected = true) }
            sync()
            startTicker()
            connected.complete(c)
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                runCatching { sync() }
                delay(500)
            }
        }
    }

    private fun sync() {
        val c = controller ?: return
        try {
            syncFromController(c)
        } catch (e: Exception) {
            // MediaController 可能在释放/重连的瞬间抛错，不应用这点抖动闪退
        }
    }

    private fun syncFromController(c: MediaController) {
        val item = c.currentMediaItem
        val bvid = item?.mediaId.orEmpty()
        val info = registry.infoFor(bvid)
        val engine = registry.snapshot(bvid)
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: info?.duration?.times(1000L)
            ?: 0L
        _state.update { previous ->
            previous.copy(
                connected = true,
                bvid = bvid,
                title = item?.mediaMetadata?.title?.toString() ?: "未在播放",
                upper = item?.mediaMetadata?.artist?.toString().orEmpty(),
                cover = item?.mediaMetadata?.artworkUri?.toString().orEmpty(),
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                playbackState = c.playbackState,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                bufferedMs = c.bufferedPosition.coerceAtLeast(0L),
                repeatMode = c.repeatMode,
                shuffle = c.shuffleModeEnabled,
                info = info,
                engine = engine,
            )
        }
        syncQueue(c)
    }

    /**
     * 把 MediaController 的当前时间线映射成 UI 可用的队列。
     *
     * MediaItem 的 metadata 由 [AutoLibrary.trackItem] 从 TrackItem 写入，标题 / UP 主 /
     * 封面 / 时长都够播放队列面板使用；intro 等列表字段不进队列，避免撑大 Binder 事务。
     *
     * 随机播放时按 ExoPlayer 当前的随机顺序重排：面板里当前行下面的一首，就是下一首真正
     * 会播放的曲目，`03 / 128` 也对应随机队列里的位置。随机顺序只在时间线 / 随机模式
     * 变化时重新展开，进度 tick 不会反复遍历。
     */
    private fun syncQueue(c: MediaController) {
        val count = c.mediaItemCount
        val timelineIndex = c.currentMediaItemIndex
        val shuffle = c.shuffleModeEnabled
        val current = _queue.value

        if (queueDirty || count != current.items.size || shuffle != current.shuffle) {
            queueDirty = false
            val order = displayOrder(c, count, shuffle)
            val items = ArrayList<TrackItem>(count)
            for (index in order) {
                items += c.getMediaItemAt(index).toTrackItem()
            }
            setQueueOrder(order)
            _queue.value = PlayerQueueState(
                items = items,
                currentIndex = positionOf(timelineIndex),
                shuffle = shuffle,
            )
            return
        }
        val position = positionOf(timelineIndex)
        if (position != current.currentIndex) {
            _queue.value = current.copy(currentIndex = position)
        }
    }

    /**
     * 面板每一行对应的播放器时间线下标。
     *
     * 顺序播放就是 0..n-1；随机播放交给 [shuffledQueueOrder] 按时间线的 ShuffleOrder
     * 展开。时间线暂时不可用时退回入队顺序，至少不会丢歌。
     */
    private fun displayOrder(c: MediaController, count: Int, shuffle: Boolean): IntArray {
        if (!shuffle || count <= 1) return IntArray(count) { it }
        val timeline: Timeline = c.currentTimeline
        if (timeline.windowCount != count) return IntArray(count) { it }
        return shuffledQueueOrder(
            count = count,
            first = { timeline.getFirstWindowIndex(true) },
            next = { index -> timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true) },
        )
    }

    private fun setQueueOrder(order: IntArray) {
        timelineIndexByPosition = order
        positionByTimelineIndex = IntArray(order.size) { -1 }
        order.forEachIndexed { position, timelineIndex ->
            if (timelineIndex in positionByTimelineIndex.indices) {
                positionByTimelineIndex[timelineIndex] = position
            }
        }
    }

    private fun positionOf(timelineIndex: Int): Int =
        positionByTimelineIndex.getOrNull(timelineIndex) ?: -1

    private suspend fun awaitController(): MediaController? {
        controller?.let { return it }
        return withTimeoutOrNull(6_000L) { connected.await() }
    }

    suspend fun playQueue(items: List<TrackItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val c = awaitController()
        if (c == null) {
            _errors.tryEmit("播放服务未就绪，请重试")
            return
        }
        val index = startIndex.coerceIn(0, items.lastIndex)
        val mediaItems = items.map { it.toMediaItem() }
        queueIds.clear()
        items.forEach { item -> item.bvid.takeIf { it.isNotBlank() }?.let(queueIds::add) }
        c.setMediaItems(mediaItems, index, 0L)
        c.prepare()
        c.play()
        // 先给 UI 一份完整的队列，不用等时间线事件再通过 Binder 逐条读回来。
        // 随机模式下这份顺序仍是入队顺序，等时间线事件到达后再按实际随机顺序展开。
        setQueueOrder(IntArray(items.size) { it })
        _queue.value = PlayerQueueState(
            items = items,
            currentIndex = index,
            shuffle = c.shuffleModeEnabled,
        )
        queueDirty = false
    }

    /**
     * 在播放队列面板里直接点某一首：面板下标按当前（可能是随机的）显示顺序
     * 换算回时间线位置，再跳过去播放。
     */
    fun playQueueItem(position: Int) {
        val c = controller ?: return
        val index = timelineIndexByPosition.getOrNull(position) ?: return
        if (index !in 0 until c.mediaItemCount) return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (index == c.currentMediaItemIndex) {
            c.play()
        } else {
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    /**
     * 把音轨补到当前队列末尾（已存在的按 bvid 跳过）。
     *
     * 随机播放需要整个收藏夹都在队列里，但一次性 setMediaItems 上千条会撑爆
     * MediaController 的 Binder 事务，所以这里分批 addMediaItems。
     * 返回实际补进去的条数。
     */
    fun appendToQueue(items: List<TrackItem>): Int {
        if (items.isEmpty()) return 0
        val c = controller ?: return 0
        val missing = missingQueueTracks(queueIds, items)
        if (missing.isEmpty()) return 0
        var added = 0
        for (batch in missing.chunked(QUEUE_APPEND_BATCH)) {
            c.addMediaItems(batch.map { it.toMediaItem() })
            batch.forEach { item -> queueIds.add(item.bvid) }
            added += batch.size
        }
        return added
    }

    fun queueSize(): Int = controller?.mediaItemCount ?: queueIds.size

    fun isShuffleEnabled(): Boolean = controller?.shuffleModeEnabled ?: false

    private fun syncQueueIds(c: MediaController) {
        queueIds.clear()
        for (index in 0 until c.mediaItemCount) {
            c.getMediaItemAt(index).mediaId.takeIf { it.isNotBlank() }?.let(queueIds::add)
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3_000L) {
            c.seekTo(0L)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        } else {
            c.seekTo(0L)
        }
    }

    fun stopAndClear() {
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        queueIds.clear()
        setQueueOrder(IntArray(0))
        _queue.value = PlayerQueueState()
        queueDirty = true
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        queueIds.clear()
        setQueueOrder(IntArray(0))
        _queue.value = PlayerQueueState()
        queueDirty = true
        controller?.let {
            it.removeListener(listener)
            it.release()
        }
        controller = null
        runCatching { MediaController.releaseFuture(future) }
    }

    private fun TrackItem.toMediaItem(): MediaItem = AutoLibrary.trackItem(this)

    private fun MediaItem.toTrackItem(): TrackItem {
        val metadata = mediaMetadata
        return TrackItem(
            bvid = mediaId,
            title = metadata.title?.toString().orEmpty().ifBlank { mediaId },
            cover = metadata.artworkUri?.toString().orEmpty(),
            upper = metadata.artist?.toString().orEmpty(),
            duration = metadata.durationMs
                ?.takeIf { it > 0L }
                ?.let { (it / 1000L).toInt() }
                ?: 0,
        )
    }

    private companion object {
        /** 每次 addMediaItems 的批量大小：太大可能触发 Binder 事务上限。 */
        const val QUEUE_APPEND_BATCH = 80
    }
}
