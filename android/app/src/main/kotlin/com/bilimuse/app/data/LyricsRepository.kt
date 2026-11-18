package com.bilimuse.app.data

import android.content.Context
import com.bilimuse.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * 歌词数据层：严格按 `docs/LYRICS.md` 的服务端规则在本地实现一遍。
 *
 * 来源阶梯（第一个可用的胜出）：
 * ```
 * 本地 LRC → pin → B 站人工字幕 → LRCLIB（≥0.62 且 track≥0.7） → B 站 AI 字幕 → none
 * ```
 * 歌词永远不能影响播放：任何网络失败都只退化成空状态，不抛给调用方。
 *
 * @param configDir 用户文件目录（`filesDir/lyrics`）：本地 LRC 与偏好。
 * @param cacheDir 抓取缓存目录（`cacheDir/lyrics`）：命中 30 天、未命中 6 小时。
 * @param bili B 站能力（[BiliClient] 实现；单测里可替换）。
 * @param settingsProvider 每次解析读取一次设置，关闭的来源直接跳过。
 * @param lrclibBaseUrl LRCLIB 根地址（单测指向 MockWebServer）。
 * @param lrclibClient LRCLIB 独立 HTTP 客户端（不发 Cookie / Referer）。
 * @param clock 缓存时间源（单测可拨快）。
 */
class LyricsRepository(
    private val configDir: File,
    private val cacheDir: File,
    private val bili: LyricsBiliSource,
    private val settingsProvider: () -> EngineSettings = { EngineSettings() },
    private val lrclibBaseUrl: String = LRCLIB_BASE,
    private val lrclibClient: OkHttpClient = defaultLrclibClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        context: Context,
        bili: LyricsBiliSource,
        settingsProvider: () -> EngineSettings,
    ) : this(
        configDir = File(context.filesDir, "lyrics"),
        cacheDir = File(context.cacheDir, "lyrics"),
        bili = bili,
        settingsProvider = settingsProvider,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 同一个 bvid 的解析用锁合并，避免切歌/重试时重复打 LRCLIB。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    // ---------------------------------------------------------------- 公开接口

    /**
     * 解析一首歌的歌词。[refresh] 为 true 时绕过缓存（只清缓存，不动本地文件与偏好）。
     *
     * bvid 非法时直接返回 `none/error`，任何情况下都不拼路径。
     */
    suspend fun resolve(
        bvid: String,
        title: String = "",
        upper: String = "",
        durationSec: Int = 0,
        refresh: Boolean = false,
    ): LyricsResult {
        if (!VALID_BVID.matches(bvid)) return LyricsResult.none(LyricsResult.REASON_ERROR)
        return lockFor(bvid).withLock {
            resolveLocked(bvid, title, upper, durationSec, refresh)
        }
    }

    /** 换源菜单：各来源可用性与标签（不写缓存）。 */
    suspend fun sources(
        bvid: String,
        title: String = "",
        upper: String = "",
        durationSec: Int = 0,
    ): List<LyricsSourceOption> {
        if (!VALID_BVID.matches(bvid)) return emptyList()
        val settings = settingsProvider()
        val demo = isDemoBvid(bvid)
        val probe = Probe(bvid)
        val items = ArrayList<LyricsSourceOption>(4)

        val localAvailable = readLocal(bvid) != null
        items += LyricsSourceOption(
            source = SOURCE_LOCAL,
            available = localAvailable,
            label = LABEL_LOCAL,
        )

        var biliAvailable = false
        var biliKind = ""
        if (!demo && settings.lyricsBili) {
            runCatching {
                val tracks = probe.subtitleTracks()
                val manual = tracks?.firstOrNull { !it.isAi }
                val ai = tracks?.firstOrNull { it.isAi && it.lan == "ai-zh" }
                    ?: tracks?.firstOrNull { it.isAi }
                if (manual != null) {
                    biliAvailable = true
                    biliKind = "manual"
                } else if (ai != null) {
                    biliAvailable = true
                    biliKind = "ai"
                }
            }
        }
        items += LyricsSourceOption(
            source = SOURCE_BILI,
            available = biliAvailable,
            label = when (biliKind) {
                "ai" -> LABEL_BILI_AI
                "manual" -> LABEL_BILI_MANUAL
                // 不可用时给中性标签：不能谎称「人工」字幕。
                else -> LABEL_BILI
            },
            kind = biliKind,
        )

        var lrclibMatched: LyricsMatched? = null
        if (!demo && settings.lyricsOnline) {
            runCatching {
                val (record, _) = findLrclib(probe, title, upper, durationSec) ?: return@runCatching
                lrclibMatched = LyricsMatched(
                    id = record.id,
                    track = record.track,
                    artist = record.artist,
                    duration = record.duration,
                )
            }
        }
        items += LyricsSourceOption(
            source = SOURCE_LRCLIB,
            available = lrclibMatched != null,
            label = LABEL_LRCLIB,
            matched = lrclibMatched,
        )

        items += LyricsSourceOption(
            source = SOURCE_DEMO,
            available = demo,
            label = LABEL_DEMO,
        )
        return items
    }

    /** LRCLIB 搜索（搜索面板），最多 10 条；关闭在线来源时直接返回空。 */
    suspend fun search(query: String): List<LyricsCandidate> {
        val keyword = query.trim()
        if (keyword.isEmpty() || !settingsProvider().lyricsOnline) return emptyList()
        val url = "$lrclibBaseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", keyword)
            .build()
        val probe = Probe("")
        return when (val fetched = fetchJson(url, probe)) {
            is Fetch.Ok -> {
                val array = fetched.element as? JsonArray ?: return emptyList()
                array.mapNotNull { parseRecord(it) }
                    .take(MAX_SEARCH_RESULTS)
                    .map { it.toCandidate() }
            }
            else -> emptyList()
        }
    }

    /** 采用某条 LRCLIB 候选并锁定（pin），之后解析优先用这条 id。 */
    suspend fun useCandidate(bvid: String, id: Long): LyricsResult {
        if (!VALID_BVID.matches(bvid)) return LyricsResult.none(LyricsResult.REASON_ERROR)
        if (!settingsProvider().lyricsOnline) {
            return LyricsResult.none(LyricsResult.REASON_DISABLED)
        }
        return lockFor(bvid).withLock {
            val prefs = readPrefs(bvid)
            val probe = Probe(bvid)
            val record = fetchRecordById(id, probe)
            if (record == null) {
                val reason = when {
                    probe.timeout -> LyricsResult.REASON_TIMEOUT
                    probe.error -> LyricsResult.REASON_ERROR
                    else -> LyricsResult.REASON_NOT_FOUND
                }
                LyricsResult.none(reason, prefs.offsetMs)
            } else {
                val result = lrclibResult(record, prefs.offsetMs)
                if (result == null) {
                    LyricsResult.none(LyricsResult.REASON_NOT_FOUND, prefs.offsetMs)
                } else {
                    writePrefs(bvid, prefs.copy(pinSource = SOURCE_LRCLIB, pinId = id))
                    saveCache(bvid, result)
                    result
                }
            }
        }
    }

    /**
     * 导入本地 LRC：[text] 按 UTF-8 落到 `lyrics/<bvid>.lrc` 并立即返回 local 结果。
     *
     * 超过 [LyricsLrc.MAX_BYTES]、bvid 非法或解析不出歌词时返回 null（调用方提示失败）。
     */
    suspend fun importLocal(bvid: String, text: String): LyricsResult? {
        if (!VALID_BVID.matches(bvid)) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > LyricsLrc.MAX_BYTES) return null
        // 统一按 UTF-8 写入：先按编码回退规则解出文本，再以 UTF-8 落盘。
        val decoded = LyricsLrc.decode(bytes)
        val parsed = LyricsLrc.parse(decoded)
        if (parsed.lines.isEmpty()) return null
        return lockFor(bvid).withLock {
            withContext(Dispatchers.IO) {
                writeFileAtomically(localFile(bvid), decoded)
                // 本地文件优先，同时清掉旧缓存，避免删除本地后读到过期的抓取结果。
                cacheFile(bvid).delete()
            }
            val prefs = readPrefs(bvid)
            LyricsResult(
                status = LyricsResult.STATUS_OK,
                source = SOURCE_LOCAL,
                sourceLabel = LABEL_LOCAL,
                synced = parsed.synced,
                offsetMs = prefs.offsetMs,
                lines = parsed.lines,
                meta = LyricsMeta(available = LyricsAvailable(local = true)),
            )
        }
    }

    /** 删除本地 LRC 与抓取缓存（保留偏移 / pin 等用户偏好）。 */
    suspend fun deleteLocal(bvid: String) {
        if (!VALID_BVID.matches(bvid)) return
        lockFor(bvid).withLock {
            withContext(Dispatchers.IO) {
                localFile(bvid).delete()
                cacheFile(bvid).delete()
            }
        }
    }

    /**
     * 设置偏移（毫秒）。范围 [-30s, +30s]，步进 0.5s 由界面控制；这里只做 clamp。
     * 按 bvid 持久化，切歌回来仍然生效。
     */
    fun setOffset(bvid: String, offsetMs: Long) {
        if (!VALID_BVID.matches(bvid)) return
        val prefs = readPrefs(bvid)
        writePrefs(bvid, prefs.copy(offsetMs = clampOffset(offsetMs)))
    }

    /**
     * 锁定歌词来源；[source] 传 null 表示回到自动阶梯。
     *
     * `lrclib` 必须带候选 [pinId]；`bili` 可以只锁来源（阶梯内部人工优先、AI 兜底）；
     * `local` / `demo` 只锁来源。非法来源直接忽略，不影响已有偏好。
     */
    fun setPin(bvid: String, source: String?, pinId: Long? = null) {
        if (!VALID_BVID.matches(bvid)) return
        val next = when {
            source == null -> null
            else -> source.trim().lowercase().takeIf { it in PIN_SOURCES }
                ?: return
        }
        if (next == SOURCE_LRCLIB && pinId == null) return
        val id = if (next == SOURCE_LRCLIB || next == SOURCE_BILI) pinId else null
        val prefs = readPrefs(bvid)
        writePrefs(bvid, prefs.copy(pinSource = next, pinId = id))
    }

    // ---------------------------------------------------------------- 来源阶梯

    private suspend fun resolveLocked(
        bvid: String,
        title: String,
        upper: String,
        durationSec: Int,
        refresh: Boolean,
    ): LyricsResult {
        val prefs = readPrefs(bvid)

        // 1. 本地 LRC：文件存在即最高优先（演示曲目也一样）。
        readLocal(bvid)?.let { parsed ->
            return LyricsResult(
                status = LyricsResult.STATUS_OK,
                source = SOURCE_LOCAL,
                sourceLabel = LABEL_LOCAL,
                synced = parsed.synced,
                offsetMs = prefs.offsetMs,
                lines = parsed.lines,
                meta = LyricsMeta(available = LyricsAvailable(local = true)),
            )
        }

        // 演示曲目没有本地文件时给演示歌词，且不联网。
        if (isDemoBvid(bvid)) return demoResult(prefs.offsetMs)

        val settings = settingsProvider()

        // 缓存只存抓取结果；偏移始终以当前偏好的最新值为准，避免调完偏移又跳回旧值。
        // 有 pin 时绝不读缓存：否则上一次自动阶梯的命中会遮蔽用户锁定的来源。
        if (!refresh && prefs.pinSource == null) {
            readCache(bvid)
                ?.takeIf { isCacheUsable(it, settings) }
                ?.let { cached -> return cached.copy(offsetMs = prefs.offsetMs) }
        }

        val probe = Probe(bvid)

        // 2. 用户锁定的来源（pin）。
        if (prefs.pinSource != null) {
            resolvePinned(bvid, prefs, upper, durationSec, settings, probe)?.let { pinned ->
                return cacheResult(bvid, prefs, pinned)
            }
        }

        // 3. B 站人工字幕（lan 不以 ai- 开头）。
        if (settings.lyricsBili) {
            biliResult(probe, prefs, ai = false)?.let { return cacheResult(bvid, prefs, it) }
        }

        // 4. LRCLIB：得分达标才用。
        if (settings.lyricsOnline) {
            val found = findLrclib(probe, title, upper, durationSec)
            if (found != null) {
                val (record, _) = found
                lrclibResult(record, prefs.offsetMs)?.let { result ->
                    return cacheResult(
                        bvid,
                        prefs,
                        result.copy(
                            meta = result.meta.copy(
                                available = LyricsAvailable(
                                    local = false,
                                    bili = probe.biliKind,
                                    lrclib = true,
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        // 5. B 站 AI 字幕（过滤占位内容后至少 4 行有效文本）。
        if (settings.lyricsBili) {
            biliResult(probe, prefs, ai = true)?.let { return cacheResult(bvid, prefs, it) }
        }

        // 6. none：按失败原因分类，交给界面显示不同空状态。
        val reason = when {
            !settings.lyricsOnline && !settings.lyricsBili -> LyricsResult.REASON_DISABLED
            probe.timeout -> LyricsResult.REASON_TIMEOUT
            probe.error -> LyricsResult.REASON_ERROR
            probe.notLoggedIn -> LyricsResult.REASON_NOT_LOGGED_IN
            else -> LyricsResult.REASON_NOT_FOUND
        }
        val none = LyricsResult.none(reason, prefs.offsetMs)
        // 设置关闭 / 未登录不该缓存：用户改完设置或登录后要立刻能重新解析。
        return if (reason == LyricsResult.REASON_DISABLED ||
            reason == LyricsResult.REASON_NOT_LOGGED_IN
        ) {
            none
        } else {
            cacheResult(bvid, prefs, none)
        }
    }

    /**
     * 写入抓取缓存，但**有 pin 时只缓存锁定来源的命中**。
     *
     * 用户锁定了某个来源却临时降级成别的内容时，绝不能把这个降级结果写进缓存，
     * 否则下次解析会一直吃缓存、永远不再尝试 pin。
     */
    private suspend fun cacheResult(
        bvid: String,
        prefs: LyricsPrefs,
        result: LyricsResult,
    ): LyricsResult {
        if (prefs.pinSource != null && result.source != prefs.pinSource) return result
        return saveCache(bvid, result)
    }

    /** pin 命中时按锁定来源解析；来源被设置关闭或本就不存在时返回 null 继续降级。 */
    private suspend fun resolvePinned(
        bvid: String,
        prefs: LyricsPrefs,
        upper: String,
        durationSec: Int,
        settings: EngineSettings,
        probe: Probe,
    ): LyricsResult? = when (prefs.pinSource) {
        SOURCE_DEMO -> demoResult(prefs.offsetMs)
        SOURCE_LRCLIB -> {
            if (!settings.lyricsOnline) {
                null
            } else {
                val id = prefs.pinId
                if (id == null) {
                    null
                } else {
                    fetchRecordById(id, probe)?.let { lrclibResult(it, prefs.offsetMs) }
                }
            }
        }
        SOURCE_BILI -> {
            if (!settings.lyricsBili) {
                null
            } else {
                biliResult(probe, prefs, ai = false) ?: biliResult(probe, prefs, ai = true)
            }
        }
        // 本地 LRC 已在阶梯最前面检查过；文件被删掉就继续降级。
        else -> null
    }

    /** 取 B 站字幕并转成歌词结果；不足 4 行或下载失败返回 null。 */
    private suspend fun biliResult(
        probe: Probe,
        prefs: LyricsPrefs,
        ai: Boolean,
    ): LyricsResult? {
        val lines = probe.biliLines(ai) ?: return null
        return LyricsResult(
            status = LyricsResult.STATUS_OK,
            source = SOURCE_BILI,
            sourceLabel = if (ai) LABEL_BILI_AI else LABEL_BILI_MANUAL,
            synced = true,
            offsetMs = prefs.offsetMs,
            lines = lines,
            meta = LyricsMeta(
                available = LyricsAvailable(local = false, bili = probe.biliKind),
            ),
        )
    }

    private fun demoResult(offsetMs: Long): LyricsResult = LyricsResult(
        status = LyricsResult.STATUS_OK,
        source = SOURCE_DEMO,
        sourceLabel = LABEL_DEMO,
        synced = true,
        offsetMs = offsetMs,
        lines = DEMO_LINES,
        meta = LyricsMeta(),
    )

    // ---------------------------------------------------------------- LRCLIB

    /**
     * 依次请求 LRCLIB，任一步有可接受结果即停止：
     * 1. 每个候选的 `track_name` + `artist_name`（artist 为空则省略参数）；
     * 2. `q=<清洗后的完整标题>`；
     * 3. `track_name=<track>` 单独。
     */
    private suspend fun findLrclib(
        probe: Probe,
        title: String,
        upper: String,
        durationSec: Int,
    ): Pair<LrclibRecord, LyricsMatch.MatchScore>? {
        val queries = LyricsMatch.candidates(title, upper)
        if (queries.isEmpty()) return null
        val collected = ArrayList<LrclibRecord>()

        suspend fun attempt(params: Map<String, String>): Pair<LrclibRecord, LyricsMatch.MatchScore>? {
            val records = searchLrclib(params, probe) ?: return null
            collected += records
            val best = LyricsMatch.best(
                candidates = collected.map { it.toCandidate() },
                queries = queries,
                upper = upper,
                videoDurationSec = durationSec,
            ) ?: return null
            val record = collected.firstOrNull { it.id == best.first.id } ?: return null
            return record to best.second
        }

        for (query in queries) {
            val params = linkedMapOf("track_name" to query.track)
            // 只有标题里解析出的歌手才当 artist_name；UP 主名只参与打分与搜索提示。
            if (query.artist.isNotBlank() && query.artistFromTitle) {
                params["artist_name"] = query.artist
            }
            attempt(params)?.let { return it }
        }
        val cleanedTitle = LyricsMatch.cleanTitle(title)
        if (cleanedTitle.isNotBlank()) {
            attempt(mapOf("q" to cleanedTitle))?.let { return it }
        }
        attempt(mapOf("track_name" to queries.first().track))?.let { return it }
        return null
    }

    private suspend fun searchLrclib(
        params: Map<String, String>,
        probe: Probe,
    ): List<LrclibRecord>? {
        val url = "$lrclibBaseUrl/api/search".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return when (val fetched = fetchJson(url, probe)) {
            is Fetch.Ok -> {
                val array = fetched.element as? JsonArray ?: return emptyList()
                array.mapNotNull { parseRecord(it) }
            }
            Fetch.Missing -> emptyList()
            is Fetch.Failed -> null
        }
    }

    private suspend fun fetchRecordById(id: Long, probe: Probe): LrclibRecord? =
        when (val fetched = fetchJson("$lrclibBaseUrl/api/get/$id".toHttpUrl(), probe)) {
            is Fetch.Ok -> parseRecord(fetched.element)
            else -> null
        }

    /** LRCLIB 独立请求：不带 Cookie / Referer / 账号信息，超时 6 秒。 */
    private suspend fun fetchJson(url: HttpUrl, probe: Probe): Fetch = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent())
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            lrclibClient.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Fetch.Missing
                    // 429 / 5xx：直接降级，不做重试轰炸。
                    response.code == 429 || response.code >= 500 -> {
                        probe.error = true
                        Fetch.Failed()
                    }
                    !response.isSuccessful -> {
                        probe.error = true
                        Fetch.Failed()
                    }
                    else -> {
                        val text = response.body?.string().orEmpty()
                        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
                        if (element == null) {
                            probe.error = true
                            Fetch.Failed()
                        } else {
                            Fetch.Ok(element)
                        }
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            probe.timeout = true
            Fetch.Failed()
        } catch (e: IOException) {
            probe.error = true
            Fetch.Failed()
        }
    }

    private fun parseRecord(element: JsonElement): LrclibRecord? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"].asLongOrNull() ?: return null
        val track = obj["trackName"].asStringOrNull().orEmpty()
        if (track.isBlank()) return null
        return LrclibRecord(
            id = id,
            track = track,
            artist = obj["artistName"].asStringOrNull().orEmpty(),
            album = obj["albumName"].asStringOrNull().orEmpty(),
            duration = obj["duration"].asIntOrNull() ?: 0,
            instrumental = obj["instrumental"].asBooleanOrNull() ?: false,
            syncedLyrics = obj["syncedLyrics"].asStringOrNull()?.takeIf { it.isNotBlank() },
            plainLyrics = obj["plainLyrics"].asStringOrNull()?.takeIf { it.isNotBlank() },
        )
    }

    /** 优先 syncedLyrics，只有 plainLyrics 时按静态歌词返回。 */
    private fun lrclibResult(record: LrclibRecord, offsetMs: Long): LyricsResult? {
        val syncText = record.syncedLyrics
        if (syncText != null) {
            val parsed = LyricsLrc.parse(syncText)
            if (parsed.lines.isNotEmpty()) return lrclibResult(record, parsed, offsetMs)
        }
        val plainText = record.plainLyrics ?: return null
        val parsed = LyricsLrc.parse(plainText)
        if (parsed.lines.isEmpty()) return null
        return lrclibResult(record, parsed, offsetMs)
    }

    private fun lrclibResult(
        record: LrclibRecord,
        parsed: LyricsLrc.ParseResult,
        offsetMs: Long,
    ): LyricsResult = LyricsResult(
        status = LyricsResult.STATUS_OK,
        source = SOURCE_LRCLIB,
        sourceLabel = LABEL_LRCLIB,
        synced = parsed.synced,
        offsetMs = offsetMs,
        lines = parsed.lines,
        meta = LyricsMeta(
            matched = LyricsMatched(
                id = record.id,
                track = record.track,
                artist = record.artist,
                duration = record.duration,
            ),
            available = LyricsAvailable(lrclib = true),
        ),
    )

    // ---------------------------------------------------------------- B 站字幕

    /** 字幕 JSON 的 `body[{from,to,content}]` → 歌词行；AI 字幕按碎片合并。 */
    private fun parseSubtitleBody(body: JsonObject, ai: Boolean): List<LyricsLine> {
        val elements = body["body"].asArrayOrNull() ?: return emptyList()
        val segments = ArrayList<SubtitleSegment>(elements.size)
        for (element in elements) {
            val item = element.asObjectOrNull() ?: continue
            val raw = item["content"].asStringOrNull()?.trim().orEmpty()
            if (raw.isEmpty() || PLACEHOLDER_LINE.matches(raw)) continue
            // AI 字幕里 ♪ ♫ ♬ ♩ 只是「整句唱词」的分段标记：显示前去掉，压缩多余空白。
            val text = if (ai) stripMusicMarks(raw) else raw
            if (text.isEmpty()) continue
            val standalone = ai && raw.any { it in MUSIC_MARKS }
            val fromMs = secondsToMillis(item["from"]) ?: continue
            val toMs = secondsToMillis(item["to"]) ?: fromMs
            segments += SubtitleSegment(fromMs, toMs, text, standalone)
        }
        if (!ai) {
            return segments.sortedBy { it.fromMs }.map { LyricsLine(it.fromMs, it.text) }
        }
        // AI 字幕是句子级碎片：间隔 ≤0.3s、合并后 ≤24 字、总跨度 ≤8s 时并成一行。
        // 带 ♪ 标记的段本身是完整唱词，独立成行，不与相邻碎片合并。
        val merged = ArrayList<LyricsLine>(segments.size)
        var current: SubtitleSegment? = null
        for (segment in segments) {
            val previous = current
            if (previous != null && !previous.standalone && !segment.standalone &&
                segment.fromMs - previous.toMs <= AI_MERGE_GAP_MS &&
                previous.text.length + segment.text.length <= AI_MERGE_MAX_CHARS &&
                maxOf(previous.toMs, segment.toMs) - previous.fromMs <= AI_MERGE_MAX_SPAN_MS
            ) {
                current = previous.copy(
                    toMs = maxOf(previous.toMs, segment.toMs),
                    text = previous.text + segment.text,
                )
            } else {
                if (previous != null) merged += LyricsLine(previous.fromMs, previous.text)
                current = segment
            }
        }
        current?.let { merged += LyricsLine(it.fromMs, it.text) }
        return merged.sortedBy { it.timeMs }
    }

    /** 去掉 AI 字幕的 ♪ 标记并把因此产生的多余空白压成一个空格。 */
    private fun stripMusicMarks(text: String): String {
        var result = text
        for (mark in MUSIC_MARKS) result = result.replace(mark.toString(), " ")
        return result.replace(WHITESPACE, " ").trim()
    }

    private fun secondsToMillis(element: JsonElement?): Long? {
        val value = (element as? JsonPrimitive)?.doubleOrNull ?: return null
        return (value * 1000).roundToLong()
    }

    /** 一次解析里的 B 站探测状态：字幕轨只拉一次，失败只记原因不抛。 */
    private inner class Probe(private val bvid: String) {
        var timeout = false
        var error = false
        var notLoggedIn = false
        var biliKind = ""
        private var tracksLoaded = false
        private var tracks: List<SubtitleTrack> = emptyList()

        suspend fun subtitleTracks(): List<SubtitleTrack>? {
            if (!tracksLoaded) {
                tracksLoaded = true
                if (!bili.loggedIn) {
                    notLoggedIn = true
                    return null
                }
                try {
                    val page = bili.pageInfo(bvid)
                    tracks = if (page.cid <= 0) emptyList() else bili.subtitleTracks(bvid, page.cid)
                } catch (e: SocketTimeoutException) {
                    timeout = true
                    return null
                } catch (e: IOException) {
                    error = true
                    return null
                } catch (e: Exception) {
                    error = true
                    return null
                }
            }
            return tracks
        }

        suspend fun biliLines(ai: Boolean): List<LyricsLine>? {
            val list = subtitleTracks() ?: return null
            val track = if (ai) {
                list.firstOrNull { it.isAi && it.lan == "ai-zh" } ?: list.firstOrNull { it.isAi }
            } else {
                list.firstOrNull { !it.isAi }
            } ?: return null
            val lines = try {
                parseSubtitleBody(bili.subtitleBody(track.url), ai = ai)
            } catch (e: SocketTimeoutException) {
                timeout = true
                return null
            } catch (e: IOException) {
                error = true
                return null
            } catch (e: Exception) {
                error = true
                return null
            }
            if (lines.isEmpty()) return null
            // 「至少 4 行」只约束 AI 转写（碎片合并后仍太少说明没有歌词）；
            // 人工字幕是 UP 主逐句录入的，1 行也必须能用。
            if (ai && lines.size < MIN_AI_LINES) return null
            biliKind = if (ai) "ai" else "manual"
            return lines
        }
    }

    // ---------------------------------------------------------------- 本地文件 / 偏好 / 缓存

    private suspend fun readLocal(bvid: String): LyricsLrc.ParseResult? =
        withContext(Dispatchers.IO) {
            val file = localFile(bvid)
            if (!file.isFile || file.length() > LyricsLrc.MAX_BYTES) return@withContext null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
            val parsed = runCatching { LyricsLrc.parseBytes(bytes) }.getOrNull()
                ?: return@withContext null
            parsed.takeIf { it.lines.isNotEmpty() }
        }

    private fun localFile(bvid: String): File = File(configDir, "$bvid.lrc")

    private fun prefsFile(bvid: String): File = File(configDir, "$bvid.json")

    private fun cacheFile(bvid: String): File = File(cacheDir, "$bvid.json")

    private fun readPrefs(bvid: String): LyricsPrefs {
        val file = prefsFile(bvid)
        if (!file.isFile) return LyricsPrefs()
        return runCatching {
            val prefs = json.decodeFromString(LyricsPrefs.serializer(), file.readText(Charsets.UTF_8))
            if (prefs.v != PREFS_VERSION) LyricsPrefs() else prefs.copy(offsetMs = clampOffset(prefs.offsetMs))
        }.getOrElse { LyricsPrefs() }
    }

    private fun writePrefs(bvid: String, prefs: LyricsPrefs) {
        runCatching {
            writeFileAtomically(
                prefsFile(bvid),
                json.encodeToString(LyricsPrefs.serializer(), prefs),
            )
        }
    }

    private fun readCache(bvid: String): LyricsResult? {
        val file = cacheFile(bvid)
        if (!file.isFile) return null
        val envelope = runCatching {
            json.decodeFromString(LyricsCacheFile.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (envelope.v != CACHE_VERSION) return null
        val ttl = if (envelope.result.status == LyricsResult.STATUS_OK) {
            OK_CACHE_TTL_MS
        } else {
            NONE_CACHE_TTL_MS
        }
        val age = clock() - envelope.cachedAt
        if (age < 0 || age > ttl) return null
        return envelope.result.copy(meta = envelope.result.meta.copy(cached = true))
    }

    private suspend fun saveCache(bvid: String, result: LyricsResult): LyricsResult {
        val envelope = LyricsCacheFile(
            cachedAt = clock(),
            result = result.copy(meta = result.meta.copy(cached = false)),
        )
        withContext(Dispatchers.IO) {
            runCatching {
                writeFileAtomically(
                    cacheFile(bvid),
                    json.encodeToString(LyricsCacheFile.serializer(), envelope),
                )
            }
        }
        return result
    }

    /**
     * 原子写：先写 `*.tmp` 再 replace，避免进程被杀 / 写一半时留下半截文件。
     *
     * 与 Python 的 tmp + `os.replace` 对齐；失败时删除 tmp，不影响已有文件。
     */
    private fun writeFileAtomically(file: File, text: String) {
        val dir = file.parentFile
        dir?.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * 同一 bvid 的解析锁；数量超过 [MAX_LOCKS] 且锁当前空闲时清理，
     * 避免长时间使用后按 bvid 无界增长。
     */
    private fun lockFor(bvid: String): Mutex {
        if (locks.size > MAX_LOCKS) {
            locks.entries.removeAll { !it.value.isLocked }
        }
        return locks.computeIfAbsent(bvid) { Mutex() }
    }

    /** 设置关闭后对应来源直接跳过：缓存里来自该来源的结果也不再用。 */
    private fun isCacheUsable(result: LyricsResult, settings: EngineSettings): Boolean =
        when (result.source) {
            SOURCE_LRCLIB -> settings.lyricsOnline
            SOURCE_BILI -> settings.lyricsBili
            else -> true
        }

    private fun clampOffset(offsetMs: Long): Long = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    // ---------------------------------------------------------------- 内部数据类型

    private sealed interface Fetch {
        data class Ok(val element: JsonElement) : Fetch
        object Missing : Fetch
        class Failed : Fetch
    }

    private data class SubtitleSegment(
        val fromMs: Long,
        val toMs: Long,
        val text: String,
        /** AI 字幕里带 ♪ 等标记的段：本身是完整唱词，独立成行不参与合并。 */
        val standalone: Boolean = false,
    )

    private data class LrclibRecord(
        val id: Long,
        val track: String,
        val artist: String,
        val album: String,
        val duration: Int,
        val instrumental: Boolean,
        val syncedLyrics: String?,
        val plainLyrics: String?,
    ) {
        fun toCandidate(): LyricsCandidate = LyricsCandidate(
            id = id,
            track = track,
            artist = artist,
            album = album,
            duration = duration,
            instrumental = instrumental,
            synced = syncedLyrics != null,
        )
    }

    /** 抓取缓存文件：带结构版本与写入时间。 */
    @Serializable
    private data class LyricsCacheFile(
        val v: Int = CACHE_VERSION,
        val cachedAt: Long = 0L,
        val result: LyricsResult,
    )

    companion object {
        /** LRCLIB 根地址。 */
        const val LRCLIB_BASE = "https://lrclib.net"

        /** 偏移上下限（毫秒）。 */
        const val MAX_OFFSET_MS = 30_000L

        /** LRCLIB 搜索最多返回条数。 */
        const val MAX_SEARCH_RESULTS = 10

        /** 演示模式 bvid 前缀（DEMO0001 / DEMO0002 / DEMO0003）。 */
        const val DEMO_PREFIX = "DEMO"

        /** 演示曲目：不联网、不搜索在线歌词。 */
        fun isDemoBvid(bvid: String): Boolean = bvid.startsWith(DEMO_PREFIX)

        const val SOURCE_LOCAL = "local"
        const val SOURCE_BILI = "bili"
        const val SOURCE_LRCLIB = "lrclib"
        const val SOURCE_DEMO = "demo"

        /** 允许锁定的来源；local / demo 只锁来源，lrclib 需要候选 id。 */
        val PIN_SOURCES: Set<String> =
            setOf(SOURCE_LOCAL, SOURCE_BILI, SOURCE_LRCLIB, SOURCE_DEMO)

        const val LABEL_LOCAL = "本地歌词"
        const val LABEL_BILI = "B 站字幕"
        const val LABEL_BILI_MANUAL = "B 站字幕（人工）"
        const val LABEL_BILI_AI = "B 站字幕（AI 转写）"
        const val LABEL_LRCLIB = "LRCLIB"
        const val LABEL_DEMO = "演示歌词"

        /** LRCLIB User-Agent，按 `docs/LYRICS.md` 固定格式。 */
        fun userAgent(): String =
            "BiliMuse/${BuildConfig.VERSION_NAME} (https://github.com/Jackiechen259/bilimuse)"

        /** LRCLIB 专用客户端：6 秒超时、不自动重试；与本机 B 站客户端完全隔离。 */
        fun defaultLrclibClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /** 合法 bvid：只允许字母数字，避免任何路径拼接风险。 */
        private val VALID_BVID = Regex("^[A-Za-z0-9]{1,24}$")
    }
}

// ---------------------------------------------------------------- 文件级常量

private const val CACHE_VERSION = 1
private const val PREFS_VERSION = 1
private const val OK_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
private const val NONE_CACHE_TTL_MS = 6L * 60 * 60 * 1000
private const val MIN_AI_LINES = 4
private const val MAX_LOCKS = 256
private const val AI_MERGE_GAP_MS = 300L
private const val AI_MERGE_MAX_CHARS = 24
private const val AI_MERGE_MAX_SPAN_MS = 8_000L

/** AI 字幕里标记「整句唱词」边界的音符；显示前去掉。 */
private val MUSIC_MARKS = charArrayOf('♪', '♫', '♬', '♩')

/** 空白压缩（AI 字幕去标记后可能留下多余空格）。 */
private val WHITESPACE = Regex("""\s+""")

/** 占位内容：`♪ 音乐 ♪` 之类，没有实际歌词。 */
private val PLACEHOLDER_LINE = Regex("""^[♪♫\s]*音乐?[♪♫\s]*$""")

/** 演示歌词：三端同一份文案，不落盘、不联网。 */
private val DEMO_LINES = listOf(
    LyricsLine(2_000L, "这是 45 秒的合成器演示音轨"),
    LyricsLine(7_000L, "由本地 BTR 引擎分块并发加载"),
    LyricsLine(12_000L, "右下角可以看到节点与实时速度"),
    LyricsLine(17_000L, "关闭多线程，缓冲会明显变慢"),
    LyricsLine(22_000L, "歌词来自本机演示数据"),
    LyricsLine(27_000L, "不访问网络，也不属于任何歌曲"),
    LyricsLine(32_000L, "打开设置可以切换三套界面风格"),
    LyricsLine(37_000L, "按 L 可以随时收起歌词"),
)
