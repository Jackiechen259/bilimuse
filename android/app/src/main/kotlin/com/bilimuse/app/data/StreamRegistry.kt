package com.bilimuse.app.data

import com.bilimuse.app.btr.BtrStream
import com.bilimuse.app.btr.EngineSnapshot
import com.bilimuse.app.btr.Stream
import com.bilimuse.app.btr.buildStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 已解析音轨的 30 分钟缓存（按 bvid），最多 48 条。
 * 和 Python 版一样，同一条音轨只在第一次播放时探测 CDN，后续复用。
 */
class StreamRegistry(
    private val bili: BiliClient,
    private val demoCdn: DemoCdn,
    private val mediaClient: OkHttpClient,
    private val settingsProvider: () -> EngineSettings,
) {
    class TrackEntry(
        val info: TrackInfo,
        val stream: Stream,
        val expiresAt: Long,
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, TrackEntry>(16, 0.75f, true) {}
    private val resolveLocks = HashMap<String, Mutex>()

    @Volatile
    var demoMode: Boolean = false
        private set

    fun setDemoMode(enabled: Boolean) {
        if (demoMode == enabled) return
        demoMode = enabled
        closeAll()
    }

    suspend fun resolve(bvid: String, title: String = ""): TrackInfo {
        synchronized(lock) { reusableEntry(bvid) }?.let { return it.info }
        val mutex = synchronized(lock) { resolveLocks.getOrPut(bvid) { Mutex() } }
        return mutex.withLock {
            synchronized(lock) { reusableEntry(bvid) }?.let { return@withLock it.info }
            val settings = settingsProvider()
            val entry = withContext(Dispatchers.IO) {
                if (demoMode) buildDemoEntry(bvid, settings) else buildRealEntry(bvid, title, settings)
            }
            synchronized(lock) {
                entries[bvid]?.stream?.close()
                entries[bvid] = entry
                while (entries.size > MAX_TRACKS) {
                    val oldest = entries.entries.firstOrNull() ?: break
                    entries.remove(oldest.key)
                    oldest.value.stream.close()
                }
            }
            entry.info
        }
    }

    /** 给 Media3 DataSource 用：解析可能要联网，阻塞在播放线程上。 */
    fun resolveBlocking(bvid: String, title: String = ""): TrackInfo =
        runBlocking { resolve(bvid, title) }

    fun streamFor(bvid: String): Stream? = synchronized(lock) { currentEntry(bvid)?.stream }

    fun infoFor(bvid: String): TrackInfo? = synchronized(lock) { currentEntry(bvid)?.info }

    fun snapshot(bvid: String): EngineSnapshot? {
        val stream = synchronized(lock) { currentEntry(bvid)?.stream } ?: return null
        return stream.snapshot()
    }

    fun applySettings(settings: EngineSettings) {
        val streams = synchronized(lock) { entries.values.map { it.stream } }
        streams.filterIsInstance<BtrStream>().forEach { it.applyLimits(settings.concurrency) }
    }

    fun closeAll() {
        val streams = synchronized(lock) {
            val all = entries.values.map { it.stream }
            entries.clear()
            all
        }
        streams.forEach { runCatching { it.close() } }
    }

    /** 播放中的音轨即使超过 TTL 也不能被回收，只有「再次解析」时才考虑过期。 */
    private fun currentEntry(bvid: String): TrackEntry? = entries[bvid]

    private fun reusableEntry(bvid: String): TrackEntry? {
        val entry = entries[bvid] ?: return null
        return if (entry.expiresAt > System.currentTimeMillis()) entry else null
    }

    private suspend fun buildRealEntry(
        bvid: String,
        title: String,
        settings: EngineSettings,
    ): TrackEntry {
        val page = bili.pageInfo(bvid)
        val play = bili.playurl(bvid, page.cid)
        val dash = play["dash"].asObjectOrNull()
            ?: throw BiliException(-404, "该视频没有可用的音频流（可能是番剧或需大会员）")
        val audio = pickAudio(dash, settings.preferLossless)
        val stream = buildStream(
            client = mediaClient,
            url = audio.url,
            backups = audio.backups,
            headers = MEDIA_HEADERS,
            concurrency = settings.concurrency,
            chunkSize = settings.chunkSize,
            btr = settings.btrEnabled,
        )
        val snap = stream.snapshot()
        val info = TrackInfo(
            bvid = bvid,
            title = title,
            size = stream.totalSize ?: 0L,
            quality = audio.quality,
            bandwidth = audio.bandwidth,
            codecs = audio.codecs,
            mime = audio.mime.substringBefore(';').ifBlank { "audio/mp4" },
            duration = page.duration,
            mode = snap.mode,
            nodes = snap.nodes.size,
        )
        return TrackEntry(info, stream, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    private suspend fun buildDemoEntry(bvid: String, settings: EngineSettings): TrackEntry {
        val track = DemoData.tracks.firstOrNull { it.bvid == bvid }
            ?: throw BiliException(-404, "演示音轨不存在")
        val file = demoCdn.ensureAudio(track.file)
        val stream = buildStream(
            client = mediaClient,
            url = demoCdn.urlFor(track.file),
            headers = mapOf("Referer" to "http://127.0.0.1"),
            concurrency = settings.concurrency,
            chunkSize = settings.chunkSize,
            btr = settings.btrEnabled,
            useMirrors = false,
        )
        val snap = stream.snapshot()
        val info = TrackInfo(
            bvid = bvid,
            title = track.title,
            size = stream.totalSize?.takeIf { it > 0 } ?: file.length(),
            quality = "演示音轨",
            bandwidth = 0,
            codecs = "wav",
            mime = "audio/wav",
            duration = track.duration,
            mode = snap.mode,
            nodes = snap.nodes.size,
        )
        return TrackEntry(info, stream, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    companion object {
        const val MAX_TRACKS = 48
        const val CACHE_TTL_MS = 30 * 60 * 1000L

        val MEDIA_HEADERS = mapOf(
            "User-Agent" to BiliClient.UA,
            "Referer" to BiliClient.REFERER,
            "Origin" to "https://www.bilibili.com",
            "Accept" to "*/*",
        )

        fun createMediaClient(demoInterceptor: DemoCdnInterceptor): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(demoInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
                .also { client ->
                    // 每条连接对应一个分块，默认的每 host 5 条会把并发卡住
                    client.dispatcher.maxRequestsPerHost = 64
                    client.dispatcher.maxRequests = 128
                }
    }
}
