package com.bilimuse.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.bilimuse.app.BiliMuseApp
import com.bilimuse.app.MainActivity
import com.bilimuse.app.R
import com.bilimuse.app.data.NotLoggedInException
import com.bilimuse.app.data.TrackItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 后台播放服务：ExoPlayer + MediaSession。
 * 通知栏 / 锁屏 / 蓝牙耳机控制由 Media3 负责，播放不依赖 Activity 存活。
 *
 * 使用 MediaLibraryService 而不是 MediaSessionService 是为了同时向 Android Auto
 * 暴露可浏览的收藏夹。Auto 通过 MediaBrowserService 协议读取媒体库树，选中条目后
 * 仍然通过同一个 MediaController 控制此处的 ExoPlayer。
 */
class PlaybackService : MediaLibraryService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var autoLibrary: AutoLibraryDataSource
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val container = (application as BiliMuseApp).container
        autoLibrary = AutoLibraryDataSource(
            bili = container.bili,
            settingsProvider = { container.settingsStore.settings.value },
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(
            BtrDataSource.Factory(container.registry),
            DefaultExtractorsFactory(),
        )
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exo

        val sessionIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaLibrarySession.Builder(this, exo, LibraryCallback())
            .setSessionActivity(pendingIntent)
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_music)
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Android Auto 的浏览回调返回 ListenableFuture，而 B站接口是 suspend 函数。
     * 这里把协程结果桥接成 Media3 需要的 Future；异常映射成媒体库错误码，
     * 避免一次网络失败导致车机端断开到服务的连接。
     */
    private fun itemFuture(
        params: MediaLibraryService.LibraryParams?,
        block: suspend () -> MediaItem?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val future = SettableFuture.create<LibraryResult<MediaItem>>()
        serviceScope.launch {
            val result: LibraryResult<MediaItem> = try {
                val item = block()
                if (item == null) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(item, params)
                }
            } catch (e: Exception) {
                LibraryResult.ofError<MediaItem>(libraryErrorCode(e))
            }
            future.set(result)
        }
        return future
    }

    private fun itemsFuture(
        params: MediaLibraryService.LibraryParams?,
        block: suspend () -> List<MediaItem>,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        serviceScope.launch {
            val result: LibraryResult<ImmutableList<MediaItem>> = try {
                LibraryResult.ofItemList(block(), params)
            } catch (e: Exception) {
                LibraryResult.ofError<ImmutableList<MediaItem>>(libraryErrorCode(e))
            }
            future.set(result)
        }
        return future
    }

    /**
     * 部分车机控制器只回传 mediaId，不带 localConfiguration。这里用浏览时缓存的
     * MediaItem 补齐 URI/metadata；即使缓存已淘汰，也能用 mediaId 重建 BTR URI。
     */
    private fun resolveMediaItemsFuture(mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        serviceScope.launch {
            val resolved = try {
                mediaItems.map { item ->
                    if (item.localConfiguration != null) {
                        item
                    } else {
                        autoLibrary.getItem(item.mediaId) ?: AutoLibrary.trackItem(
                            TrackItem(
                                bvid = item.mediaId,
                                title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { item.mediaId },
                                upper = item.mediaMetadata.artist?.toString().orEmpty(),
                                cover = item.mediaMetadata.artworkUri?.toString().orEmpty(),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                // 解析失败时把原条目交回给 Media3，由播放器给出明确的播放错误，
                // 不要让 Future 永远不完成。
                mediaItems
            }
            future.set(resolved)
        }
        return future
    }

    private fun libraryErrorCode(error: Throwable): Int = when (error) {
        is NotLoggedInException -> LibraryResult.RESULT_ERROR_PERMISSION_DENIED
        is IOException -> LibraryResult.RESULT_ERROR_IO
        else -> LibraryResult.RESULT_ERROR_UNKNOWN
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            itemFuture(params) { autoLibrary.rootItem() }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            itemFuture(/* params= */ null) { autoLibrary.getItem(mediaId) }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            itemsFuture(params) { autoLibrary.getChildren(parentId, page, pageSize) }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = resolveMediaItemsFuture(mediaItems)
    }

    private companion object {
        const val CHANNEL_ID = "bilimuse_playback"
    }
}
