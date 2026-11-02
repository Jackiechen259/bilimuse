package com.bilimuse.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bilimuse.app.data.BiliClient
import com.bilimuse.app.data.EngineSettings
import com.bilimuse.app.data.Folder
import com.bilimuse.app.data.TrackItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android Auto 的媒体库树。
 *
 * 根节点列出当前账号的收藏夹；收藏夹是 browsable folder，里面的每个稿件是
 * playable item。播放项仍然走 [BtrDataSource] 的 bilimuse://track/{bvid}，所以
 * Android Auto 和手机 UI 使用同一条 BTR 播放链路。
 */
internal object AutoLibrary {
    const val ROOT_ID = "bilimuse:root"
    private const val FOLDER_PREFIX = "bilimuse:folder:"
    private const val TRACK_URI_PREFIX = "bilimuse://track/"

    fun folderMediaId(folderId: Long): String = "$FOLDER_PREFIX$folderId"

    /** 只接受根节点下的收藏夹 ID，避免把任意 mediaId 当成数字。 */
    fun folderId(mediaId: String): Long? {
        if (!mediaId.startsWith(FOLDER_PREFIX)) return null
        return mediaId.removePrefix(FOLDER_PREFIX).toLongOrNull()
    }

    fun trackUri(bvid: String): Uri = Uri.parse("$TRACK_URI_PREFIX$bvid")

    fun rootItem(loggedIn: Boolean): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("BiliMuse")
            .setSubtitle(
                if (loggedIn) "B站收藏夹" else "请先在手机打开 BiliMuse 完成登录",
            )
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(metadata)
            .build()
    }

    fun folderItem(folder: Folder): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(folder.title)
            .setSubtitle("${folder.count} 首")
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId(folderMediaId(folder.id))
            .setMediaMetadata(metadata)
            .build()
    }

    fun trackItem(track: TrackItem): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.upper.ifBlank { "B站收藏夹" })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply {
                if (track.cover.isNotBlank()) setArtworkUri(Uri.parse(track.cover))
                if (track.duration > 0) setDurationMs(track.duration * 1000L)
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(track.bvid)
            .setUri(trackUri(track.bvid))
            .setMediaMetadata(metadata)
            .build()
    }
}

/**
 * 把 BiliClient 的收藏夹 API 接到 Media3 的 MediaLibrarySession 回调。
 *
 * Media3 会按 page/pageSize 分页调用 [getChildren]。这里用固定大小的 B站分页
 * 维护收藏夹缓存，再按 Auto 请求的窗口切片；条目和收藏夹元数据也会做短期缓存，
 * 避免 Auto 在 subscribe/getItem/getChildren 之间重复请求网络。
 */
internal class AutoLibraryDataSource(
    private val bili: BiliClient,
    private val settingsProvider: () -> EngineSettings,
) {
    private val itemCache = object : LinkedHashMap<String, MediaItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?): Boolean =
            size > MAX_CACHED_ITEMS
    }
    private val itemLock = Any()

    private val folderMutex = Mutex()
    private val folderLock = Any()
    private var cachedFolders: List<Folder>? = null
    private var foldersFetchedAt = 0L

    private val folderItemsMutex = Mutex()
    private val folderItemsLock = Any()
    private val folderItemsCache = HashMap<Long, FolderItemsState>()

    fun rootItem(): MediaItem = AutoLibrary.rootItem(bili.loggedIn)

    suspend fun getItem(mediaId: String): MediaItem? {
        if (mediaId == AutoLibrary.ROOT_ID) return rootItem()
        val folderId = AutoLibrary.folderId(mediaId)
        if (folderId != null && folderId in hiddenFolderIds()) return null
        cachedItem(mediaId)?.let { return it }
        if (folderId == null) return null

        val folder = folders().firstOrNull { it.id == folderId } ?: return null
        return cache(AutoLibrary.folderItem(folder))
    }

    suspend fun getChildren(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        if (parentId == AutoLibrary.ROOT_ID) {
            if (!bili.loggedIn) return emptyList()
            return visibleFolders().map { folder -> cache(AutoLibrary.folderItem(folder)) }
        }

        val folderId = AutoLibrary.folderId(parentId) ?: return emptyList()
        if (folderId in hiddenFolderIds()) return emptyList()
        val size = pageSize.takeIf { it > 0 }?.coerceAtMost(MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
        val offset = page.coerceAtLeast(0).toLong() * size
        if (offset > Int.MAX_VALUE) return emptyList()
        val endExclusive = offset + size
        val tracks = folderTracks(folderId, endExclusive)
        val from = offset.toInt().coerceAtMost(tracks.size)
        val to = endExclusive.coerceAtMost(tracks.size.toLong()).toInt()
        return tracks.subList(from, to).map { track -> cache(AutoLibrary.trackItem(track)) }
    }

    /**
     * 按 Auto 请求的 offset 从固定大小的 B站分页缓存里切片。
     *
     * 不能把 Auto 的 pageSize 直接当成 B站接口的 ps：两者不一定一致（B站可能
     * 限制单页条数），直接换算会导致客户端误以为已经到末尾。这里统一用固定
     * BILI_PAGE_SIZE 拉取，再满足任意 pageSize 的窗口请求。
     */
    private suspend fun folderTracks(folderId: Long, endExclusive: Long): List<TrackItem> =
        folderItemsMutex.withLock {
            val state = synchronized(folderItemsLock) {
                val now = System.currentTimeMillis()
                folderItemsCache[folderId]
                    ?.takeIf { now - it.fetchedAt < FOLDER_ITEMS_CACHE_TTL_MS }
                    ?: FolderItemsState(fetchedAt = now).also { folderItemsCache[folderId] = it }
            }

            while (state.hasMore &&
                state.tracks.size.toLong() < endExclusive &&
                state.nextApiPage <= MAX_FETCH_PAGES
            ) {
                val result = bili.folderItems(
                    mediaId = folderId,
                    page = state.nextApiPage,
                    size = BILI_PAGE_SIZE,
                )
                var added = 0
                for (track in result.items) {
                    if (track.bvid.isBlank() || !state.seenBvids.add(track.bvid)) continue
                    state.tracks += track
                    added++
                }
                state.nextApiPage++
                state.hasMore = result.hasMore
                if (result.items.isEmpty()) {
                    state.emptyPages++
                    if (state.emptyPages >= MAX_EMPTY_PAGES) state.hasMore = false
                } else if (added == 0) {
                    // 接口忽略 pn 时会一直返回同一页，停止以避免死循环。
                    state.hasMore = false
                } else {
                    state.emptyPages = 0
                }
            }
            state.tracks.toList()
        }

    private fun hiddenFolderIds(): Set<Long> = settingsProvider().hiddenFolderIds

    private suspend fun visibleFolders(): List<Folder> {
        val hidden = hiddenFolderIds()
        return folders().filterNot { it.id in hidden }
    }

    private suspend fun folders(): List<Folder> {
        synchronized(folderLock) {
            if (System.currentTimeMillis() - foldersFetchedAt < FOLDER_CACHE_TTL_MS) {
                cachedFolders?.let { return it }
            }
        }
        return folderMutex.withLock {
            synchronized(folderLock) {
                if (System.currentTimeMillis() - foldersFetchedAt < FOLDER_CACHE_TTL_MS) {
                    cachedFolders?.let { return@withLock it }
                }
            }
            val fresh = bili.folders()
            synchronized(folderLock) {
                cachedFolders = fresh
                foldersFetchedAt = System.currentTimeMillis()
            }
            fresh
        }
    }

    private fun cachedItem(mediaId: String): MediaItem? =
        synchronized(itemLock) { itemCache[mediaId] }

    private fun cache(item: MediaItem): MediaItem {
        synchronized(itemLock) { itemCache[item.mediaId] = item }
        return item
    }

    private class FolderItemsState(val fetchedAt: Long) {
        val tracks = ArrayList<TrackItem>()
        val seenBvids = HashSet<String>()
        var nextApiPage = 1
        var hasMore = true
        var emptyPages = 0
    }

    private companion object {
        const val BILI_PAGE_SIZE = 30
        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 200
        const val MAX_CACHED_ITEMS = 600
        const val MAX_FETCH_PAGES = 200
        const val MAX_EMPTY_PAGES = 3
        const val FOLDER_CACHE_TTL_MS = 30_000L
        const val FOLDER_ITEMS_CACHE_TTL_MS = 2 * 60 * 1000L
    }
}
