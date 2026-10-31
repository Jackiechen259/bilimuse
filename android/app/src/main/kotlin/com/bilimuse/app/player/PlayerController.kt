package com.bilimuse.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bilimuse.app.btr.EngineSnapshot
import com.bilimuse.app.data.StreamRegistry
import com.bilimuse.app.data.TrackInfo
import com.bilimuse.app.data.TrackItem
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

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
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
    }

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
        c.setMediaItems(mediaItems, index, 0L)
        c.prepare()
        c.play()
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
        controller?.let {
            it.removeListener(listener)
            it.release()
        }
        controller = null
        runCatching { MediaController.releaseFuture(future) }
    }

    private fun TrackItem.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(upper.ifBlank { "B站收藏夹" })
            .apply {
                if (cover.isNotBlank()) setArtworkUri(Uri.parse(cover))
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(bvid)
            .setUri("bilimuse://track/$bvid")
            .setMediaMetadata(metadata)
            .build()
    }
}
