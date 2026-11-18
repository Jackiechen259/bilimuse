package com.bilimuse.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 歌词仓库：来源阶梯、缓存、B 站字幕、LRCLIB 与安全约束。 */
class LyricsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var configDir: File
    private lateinit var cacheDir: File
    private lateinit var fakeBili: FakeBili
    private var settings: EngineSettings = EngineSettings()
    private var now: Long = 1_700_000_000_000L

    private val bvid = "BV1xx411c7mD"
    private val demoBvid = "DEMO0001"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = Files.createTempDirectory("bilimuse-lyrics").toFile()
        configDir = File(root, "config")
        cacheDir = File(root, "cache")
        fakeBili = FakeBili()
        settings = EngineSettings()
        now = 1_700_000_000_000L
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------ LRCLIB

    @Test
    fun resolveSearchesLrclibAndParsesSyncedLyrics() {
        respond { request ->
            if (request.requestUrl!!.encodedPath == "/api/search") {
                json(JsonArray(listOf(recordJson())))
            } else {
                MockResponse().setResponseCode(404)
            }
        }
        val result = runBlocking {
            repository().resolve(bvid, "【4K】夜航星 - 不才", "不才", 304)
        }
        assertEquals("ok", result.status)
        assertEquals("lrclib", result.source)
        assertEquals("LRCLIB", result.sourceLabel)
        assertTrue(result.synced)
        assertEquals(2, result.lines.size)
        assertEquals(12_000L, result.lines[0].timeMs)
        assertEquals("执念的鱼", result.lines[0].text)
        assertEquals(31664182L, result.meta.matched?.id)
        assertEquals("夜航星", result.meta.matched?.track)
        assertFalse(result.meta.cached)

        val request = server.takeRequest()
        assertEquals("/api/search", request.requestUrl!!.encodedPath)
        assertEquals("夜航星", request.requestUrl!!.queryParameter("track_name"))
        assertEquals("不才", request.requestUrl!!.queryParameter("artist_name"))
        assertNull("LRCLIB 请求不能带 Cookie", request.getHeader("Cookie"))
        assertNull("LRCLIB 请求不能带 Referer", request.getHeader("Referer"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("BiliMuse/"))
    }

    @Test
    fun searchMapsAtMostTenCandidates() {
        val records = JsonArray(
            (1..12).map { id ->
                recordJson(id = id.toLong(), syncedLyrics = null, plainLyrics = "纯文本歌词")
            },
        )
        respond { json(records) }
        val candidates = runBlocking { repository().search("夜航星 不才") }
        assertEquals(10, candidates.size)
        assertEquals(1L, candidates[0].id)
        assertEquals("夜航星", candidates[0].track)
        assertEquals("不才", candidates[0].artist)
        assertEquals(304, candidates[0].duration)
        assertFalse(candidates[0].instrumental)
        assertFalse(candidates[0].synced)

        val request = server.takeRequest()
        assertEquals("/api/search", request.requestUrl!!.encodedPath)
        assertEquals("夜航星 不才", request.requestUrl!!.queryParameter("q"))
    }

    @Test
    fun searchRespectsOnlineSetting() {
        settings = EngineSettings(lyricsOnline = false)
        respond { MockResponse().setResponseCode(500) }
        val candidates = runBlocking { repository().search("夜航星") }
        assertTrue(candidates.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun useCandidateLocksPinnedLrclibId() {
        respond { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/get/42" -> json(recordJson(id = 42L))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repo = repository()
        val used = runBlocking { repo.useCandidate(bvid, 42L) }
        assertEquals("ok", used.status)
        assertEquals("lrclib", used.source)
        assertEquals(42L, used.meta.matched?.id)

        // refresh=true 绕过缓存，走 pin（按 id 取），而不是重新搜索。
        val pinned = runBlocking { repo.resolve(bvid, "随便什么标题", "某UP", 0, refresh = true) }
        assertEquals("ok", pinned.status)
        assertEquals(42L, pinned.meta.matched?.id)
        val paths = (1..server.requestCount).map { server.takeRequest().requestUrl!!.encodedPath }
        assertEquals(listOf("/api/get/42", "/api/get/42"), paths)
    }

    // ------------------------------------------------------------ 本地与缓存

    @Test
    fun localFileHasHighestPriority() {
        writeLocal(bvid, "[00:01.00]本地歌词\n[00:02.00]第二行")
        respond { MockResponse().setResponseCode(500) }
        val result = runBlocking { repository().resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("ok", result.status)
        assertEquals("local", result.source)
        assertEquals("本地歌词", result.sourceLabel)
        assertTrue(result.synced)
        assertEquals(2, result.lines.size)
        assertEquals(0, server.requestCount)
        assertEquals(0, fakeBili.totalCalls)
    }

    @Test
    fun cacheHitSkipsRequestsAndRefreshBypasses() {
        settings = EngineSettings(lyricsBili = false)
        respond { json(JsonArray(listOf(recordJson()))) }
        val repo = repository()

        val first = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("ok", first.status)
        assertFalse(first.meta.cached)
        assertEquals(1, server.requestCount)

        val second = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("ok", second.status)
        assertTrue("命中缓存应标记 cached", second.meta.cached)
        assertEquals(1, server.requestCount)

        val refreshed = runBlocking {
            repo.resolve(bvid, "夜航星 - 不才", "不才", 304, refresh = true)
        }
        assertEquals("ok", refreshed.status)
        assertFalse(refreshed.meta.cached)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cacheHitUsesLatestOffset() {
        settings = EngineSettings(lyricsBili = false)
        respond { json(JsonArray(listOf(recordJson()))) }
        val repo = repository()
        val first = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals(0L, first.offsetMs)
        assertEquals(1, server.requestCount)

        repo.setOffset(bvid, 500L)
        val second = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertTrue(second.meta.cached)
        assertEquals("缓存命中时偏移应取最新偏好", 500L, second.offsetMs)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cacheFromDisabledSourceIsIgnored() {
        settings = EngineSettings(lyricsOnline = true, lyricsBili = false)
        respond { json(JsonArray(listOf(recordJson()))) }
        val repo = repository()
        assertEquals("lrclib", runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }.source)

        settings = EngineSettings(lyricsOnline = false, lyricsBili = false)
        val result = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("none", result.status)
        assertEquals("disabled", result.reason)
        assertEquals("关闭来源后不应吃该来源的缓存", 1, server.requestCount)
    }

    @Test
    fun okCacheIsFreshForThirtyDays() {
        settings = EngineSettings(lyricsBili = false)
        respond { json(JsonArray(listOf(recordJson()))) }
        val repo = repository()
        runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals(1, server.requestCount)

        now += TimeUnit.DAYS.toMillis(29)
        runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("30 天内命中缓存", 1, server.requestCount)

        now += TimeUnit.DAYS.toMillis(2)
        runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("超过 30 天应重新请求", 2, server.requestCount)
    }

    @Test
    fun notFoundCacheIsFreshForSixHours() {
        settings = EngineSettings(lyricsBili = false)
        respond { json(JsonArray(emptyList())) }
        val repo = repository()

        val first = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("none", first.status)
        assertEquals("not_found", first.reason)
        // 候选 1（track+artist）→ q=完整标题 → track 单独，共三步。
        assertEquals(3, server.requestCount)

        val second = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("none", second.status)
        assertEquals(3, server.requestCount)

        now += TimeUnit.HOURS.toMillis(7)
        runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("超过 6 小时应重新请求", 6, server.requestCount)
    }

    // ------------------------------------------------------------ B 站字幕

    @Test
    fun resolvesManualBiliSubtitleBeforeLrclib() {
        settings = EngineSettings(lyricsOnline = false, lyricsBili = true)
        fakeBili.loggedIn = true
        fakeBili.tracks = listOf(
            SubtitleTrack(
                lan = "ai-zh",
                lanDoc = "中文（AI生成）",
                url = "https://i0.hdslb.com/ai.json",
                isAi = true,
            ),
            SubtitleTrack(
                lan = "zh-CN",
                lanDoc = "中文（中国）",
                url = "https://i0.hdslb.com/manual.json",
                isAi = false,
            ),
        )
        fakeBili.body = subtitleJson(
            Triple(1.0, 2.0, "执念的鱼"),
            Triple(2.5, 3.5, "我"),
            Triple(4.0, 5.0, "挣扎在"),
            Triple(5.5, 6.5, "水面"),
            Triple(7.0, 8.0, "之上"),
        )
        val result = runBlocking { repository().resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("ok", result.status)
        assertEquals("bili", result.source)
        assertEquals("B 站字幕（人工）", result.sourceLabel)
        assertTrue(result.synced)
        assertEquals(5, result.lines.size)
        assertEquals(1_000L, result.lines[0].timeMs)
        assertEquals("执念的鱼", result.lines[0].text)
        assertEquals(1, fakeBili.subtitleBodyCalls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mergesAiSubtitleFragmentsAndDropsPlaceholders() {
        settings = EngineSettings(lyricsOnline = false, lyricsBili = true)
        fakeBili.loggedIn = true
        fakeBili.tracks = listOf(
            SubtitleTrack(
                lan = "ai-zh",
                lanDoc = "中文（AI生成）",
                url = "https://i0.hdslb.com/ai.json",
                isAi = true,
            ),
        )
        fakeBili.body = subtitleJson(
            Triple(0.0, 1.0, "♪ 音乐 ♪"),
            Triple(2.0, 3.0, "这是第"),
            Triple(3.2, 4.0, "一句"),
            Triple(5.0, 6.0, "这是第"),
            Triple(6.2, 7.0, "二句"),
            Triple(8.0, 9.0, "这是第"),
            Triple(9.2, 10.0, "三句"),
            Triple(11.0, 12.0, "这是第"),
            Triple(12.2, 13.0, "四句"),
        )
        val result = runBlocking { repository().resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("ok", result.status)
        assertEquals("bili", result.source)
        assertEquals("B 站字幕（AI 转写）", result.sourceLabel)
        assertEquals(4, result.lines.size)
        assertEquals(2_000L, result.lines[0].timeMs)
        assertEquals("这是第一句", result.lines[0].text)
        assertEquals(5_000L, result.lines[1].timeMs)
        assertEquals("这是第二句", result.lines[1].text)
    }

    @Test
    fun biliSubtitleWithFewerThanFourLinesFallsThrough() {
        settings = EngineSettings(lyricsOnline = false, lyricsBili = true)
        fakeBili.tracks = listOf(
            SubtitleTrack("zh-CN", "中文（中国）", "https://i0.hdslb.com/manual.json", false),
        )
        fakeBili.body = subtitleJson(
            Triple(1.0, 2.0, "只有"),
            Triple(2.0, 3.0, "两行"),
        )
        val result = runBlocking { repository().resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("none", result.status)
        assertEquals("not_found", result.reason)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun disabledSettingsSkipBothOnlineSources() {
        settings = EngineSettings(lyricsOnline = false, lyricsBili = false)
        respond { MockResponse().setResponseCode(500) }
        val repo = repository()
        val result = runBlocking { repo.resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("none", result.status)
        assertEquals("disabled", result.reason)
        assertEquals(0, server.requestCount)
        assertEquals(0, fakeBili.totalCalls)
    }

    @Test
    fun disablingBiliSkipsSubtitleRequests() {
        settings = EngineSettings(lyricsOnline = true, lyricsBili = false)
        respond { json(JsonArray(listOf(recordJson()))) }
        val result = runBlocking { repository().resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("lrclib", result.source)
        assertEquals(0, fakeBili.totalCalls)
    }

    // ------------------------------------------------------------ 演示模式

    @Test
    fun demoModeReturnsFixedLyricsWithoutNetwork() {
        val repo = repository()
        repo.setOffset(demoBvid, 500L)
        val result = runBlocking { repo.resolve(demoBvid, "", "", 45) }
        assertEquals("ok", result.status)
        assertEquals("demo", result.source)
        assertEquals("演示歌词", result.sourceLabel)
        assertTrue(result.synced)
        assertEquals(8, result.lines.size)
        assertEquals(2_000L, result.lines[0].timeMs)
        assertEquals("这是 45 秒的合成器演示音轨", result.lines[0].text)
        assertEquals(37_000L, result.lines[7].timeMs)
        assertEquals("按 L 可以随时收起歌词", result.lines[7].text)
        assertEquals(500L, result.offsetMs)
        assertEquals(0, server.requestCount)
        assertEquals(0, fakeBili.totalCalls)
    }

    // ------------------------------------------------------------ 导入 / 偏移

    @Test
    fun importsLocalLyricsPersistsOffsetAndDeletes() {
        respond { MockResponse().setResponseCode(500) }
        val repo = repository()
        val imported = runBlocking { repo.importLocal(bvid, "[00:01.00]本地\n[00:02.00]歌词") }
        assertNotNull(imported)
        assertEquals("local", imported!!.source)
        assertEquals(2, imported.lines.size)
        assertTrue(File(configDir, "$bvid.lrc").exists())

        repo.setOffset(bvid, 700L)
        settings = EngineSettings(lyricsOnline = false, lyricsBili = false)
        val resolved = runBlocking { repo.resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("local", resolved.source)
        assertEquals(700L, resolved.offsetMs)

        runBlocking { repo.deleteLocal(bvid) }
        assertFalse(File(configDir, "$bvid.lrc").exists())
        val afterDelete = runBlocking { repo.resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("none", afterDelete.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun offsetIsClampedToThirtySeconds() {
        writeLocal(bvid, "[00:01.00]A")
        settings = EngineSettings(lyricsOnline = false, lyricsBili = false)
        val repo = repository()
        repo.setOffset(bvid, 99_000L)
        assertEquals(30_000L, runBlocking { repo.resolve(bvid) }.offsetMs)
        repo.setOffset(bvid, -99_000L)
        assertEquals(-30_000L, runBlocking { repo.resolve(bvid) }.offsetMs)
    }

    @Test
    fun rejectsInvalidBvidAndOversizeImport() {
        val repo = repository()
        val bad = runBlocking { repo.resolve("../etc/passwd", "x", "y", 0) }
        assertEquals("none", bad.status)
        assertEquals("error", bad.reason)
        assertNull(runBlocking { repo.importLocal("bad bvid!", "[00:01.00]x") })
        assertNull(runBlocking { repo.importLocal(bvid, "啊".repeat(100_000)) })
        assertFalse(File(configDir, "$bvid.lrc").exists())
    }

    // ------------------------------------------------------------ 换源菜单

    @Test
    fun sourcesReportsAvailability() {
        writeLocal(bvid, "[00:01.00]本地")
        fakeBili.tracks = listOf(
            SubtitleTrack("zh-CN", "中文（中国）", "https://i0.hdslb.com/manual.json", false),
        )
        respond { json(JsonArray(listOf(recordJson()))) }
        val items = runBlocking { repository().sources(bvid, "夜航星", "不才", 304) }
        assertEquals(listOf("local", "bili", "lrclib", "demo"), items.map { it.source })
        assertTrue(items[0].available)
        assertEquals("本地歌词", items[0].label)
        assertTrue(items[1].available)
        assertEquals("manual", items[1].kind)
        assertEquals("B 站字幕（人工）", items[1].label)
        assertTrue(items[2].available)
        assertEquals("LRCLIB", items[2].label)
        assertEquals("夜航星", items[2].matched?.track)
        assertFalse(items[3].available)
    }

    // ------------------------------------------------------------ BiliClient 字幕接口

    @Test
    fun subtitleTracksParsesAndOrdersManualFirst() {
        val payload = JsonObject(
            mapOf(
                "code" to JsonPrimitive(0),
                "data" to buildJsonObject {
                    put(
                        "subtitle",
                        buildJsonObject {
                            put(
                                "subtitles",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("lan", "ai-zh")
                                            put("lan_doc", "中文（AI生成）")
                                            put("subtitle_url", "//i0.hdslb.com/ai.json")
                                        },
                                        buildJsonObject {
                                            put("lan", "zh-CN")
                                            put("lan_doc", "中文（中国）")
                                            put("subtitle_url", "//i0.hdslb.com/manual.json")
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                },
            ),
        )
        server.enqueue(json(payload))
        val client = BiliClient(
            MemorySessionSink(),
            apiBase = server.url("/").toString().trimEnd('/'),
        )
        val tracks = runBlocking { client.subtitleTracks(bvid, 123L) }
        assertEquals(2, tracks.size)
        assertFalse(tracks[0].isAi)
        assertEquals("zh-CN", tracks[0].lan)
        assertEquals("中文（中国）", tracks[0].lanDoc)
        assertEquals("https://i0.hdslb.com/manual.json", tracks[0].url)
        assertTrue(tracks[1].isAi)

        val request = server.takeRequest()
        assertEquals("/x/player/v2", request.requestUrl!!.encodedPath)
        assertEquals(bvid, request.requestUrl!!.queryParameter("bvid"))
        assertEquals("123", request.requestUrl!!.queryParameter("cid"))
    }

    @Test
    fun subtitleUrlWhitelistOnlyAllowsBilibiliHttpsHosts() {
        assertNull(BiliClient.resolveSubtitleUrl("https://evil.example.com/a.json"))
        assertNull(BiliClient.resolveSubtitleUrl("http://i0.hdslb.com/a.json"))
        assertNull(BiliClient.resolveSubtitleUrl("https://i0.hdslb.com.evil.com/a.json"))
        assertNull(BiliClient.resolveSubtitleUrl(""))
        assertEquals(
            "https://i0.hdslb.com/a.json",
            BiliClient.resolveSubtitleUrl("//i0.hdslb.com/a.json"),
        )
        assertEquals(
            "https://www.bilibili.com/x/subtitle.json",
            BiliClient.resolveSubtitleUrl("/x/subtitle.json"),
        )
        assertEquals(
            "https://api.bilibili.com/x/a.json",
            BiliClient.resolveSubtitleUrl("https://api.bilibili.com/x/a.json"),
        )
        assertEquals(
            "https://upos-sz.bilivideo.com/a.json",
            BiliClient.resolveSubtitleUrl("https://upos-sz.bilivideo.com/a.json"),
        )
    }

    @Test
    fun subtitleBodyRejectsUntrustedUrlBeforeNetwork() {
        val client = BiliClient(MemorySessionSink())
        val error = runCatching {
            runBlocking { client.subtitleBody("https://evil.example.com/a.json") }
        }.exceptionOrNull()
        assertTrue("外域字幕地址必须直接拒绝", error is BiliException)
    }

    @Test
    fun disabledResultIsNotCached() {
        settings = EngineSettings(lyricsOnline = false, lyricsBili = false)
        val repo = repository()
        val disabled = runBlocking { repo.resolve(bvid, "夜航星", "不才", 304) }
        assertEquals("disabled", disabled.reason)
        assertEquals(0, server.requestCount)

        // 用户重新打开在线来源后必须立刻联网，而不是吃 6 小时的 disabled 缓存。
        settings = EngineSettings(lyricsOnline = true, lyricsBili = false)
        respond { json(JsonArray(listOf(recordJson()))) }
        val result = runBlocking { repo.resolve(bvid, "夜航星 - 不才", "不才", 304) }
        assertEquals("lrclib", result.source)
    }

    @Test
    fun pageInfoUsesConfiguredApiBase() {
        val payload = JsonObject(
            mapOf(
                "code" to JsonPrimitive(0),
                "data" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("cid", 456L)
                            put("duration", 300)
                            put("part", "P1")
                        },
                    ),
                ),
            ),
        )
        server.enqueue(json(payload))
        val client = BiliClient(
            MemorySessionSink(),
            apiBase = server.url("/").toString().trimEnd('/'),
        )
        val page = runBlocking { client.pageInfo(bvid) }
        assertEquals(456L, page.cid)
        assertEquals(300, page.duration)
        assertEquals("/x/player/pagelist", server.takeRequest().requestUrl!!.encodedPath)
    }

    // ------------------------------------------------------------ 辅助

    private fun repository(): LyricsRepository = LyricsRepository(
        configDir = configDir,
        cacheDir = cacheDir,
        bili = fakeBili,
        settingsProvider = { settings },
        lrclibBaseUrl = server.url("/").toString().trimEnd('/'),
        clock = { now },
    )

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
    }

    private fun json(element: JsonElement): MockResponse =
        MockResponse().setBody(element.toString()).setHeader("Content-Type", "application/json")

    private fun recordJson(
        id: Long = 31_664_182L,
        track: String = "夜航星",
        artist: String = "不才",
        duration: Int = 304,
        instrumental: Boolean = false,
        syncedLyrics: String? = "[00:12.00]执念的鱼\n[00:15.00]我",
        plainLyrics: String? = "执念的鱼\n我",
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("trackName", track)
        put("artistName", artist)
        put("albumName", "")
        put("duration", duration)
        put("instrumental", instrumental)
        put("plainLyrics", plainLyrics)
        put("syncedLyrics", syncedLyrics)
    }

    /** 构造 B 站字幕 body：[from, to, content]。 */
    private fun subtitleJson(vararg segments: Triple<Double, Double, String>): JsonObject =
        buildJsonObject {
            put(
                "body",
                JsonArray(
                    segments.map { (from, to, content) ->
                        buildJsonObject {
                            put("from", from)
                            put("to", to)
                            put("content", content)
                        }
                    },
                ),
            )
        }

    private fun writeLocal(bvid: String, text: String) {
        configDir.mkdirs()
        File(configDir, "$bvid.lrc").writeText(text, Charsets.UTF_8)
    }

    private class MemorySessionSink : SessionSink {
        private var data = SessionData()
        override fun load(): SessionData = data
        override fun save(data: SessionData) {
            this.data = data
        }

        override fun clear() {
            data = SessionData()
        }
    }

    private class FakeBili : LyricsBiliSource {
        override var loggedIn: Boolean = true
        var tracks: List<SubtitleTrack> = emptyList()
        var body: JsonObject = JsonObject(emptyMap())
        var pageInfoCalls = 0
        var subtitleTrackCalls = 0
        var subtitleBodyCalls = 0
        val totalCalls: Int get() = pageInfoCalls + subtitleTrackCalls + subtitleBodyCalls

        override suspend fun pageInfo(bvid: String): BiliClient.PageInfo {
            pageInfoCalls++
            return BiliClient.PageInfo(cid = 7L, duration = 0, part = "P1")
        }

        override suspend fun subtitleTracks(bvid: String, cid: Long): List<SubtitleTrack> {
            subtitleTrackCalls++
            return tracks
        }

        override suspend fun subtitleBody(url: String): JsonObject {
            subtitleBodyCalls++
            return body
        }
    }
}
