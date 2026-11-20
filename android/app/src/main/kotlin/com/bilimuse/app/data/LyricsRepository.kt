package com.bilimuse.app.data

import android.content.Context
import com.bilimuse.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
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

    // 查询结果只驻内存：key 是已发给 LRCLIB 的清洗后查询参数，不落盘
    private val searchCacheLock = Any()
    private val searchCache = LinkedHashMap<String, SearchCacheEntry>()
    private val searchInflight =
        ConcurrentHashMap<String, Deferred<List<LrclibRecord>?>>()
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lrclibSemaphore = Semaphore(LRCLIB_CONCURRENCY)

    // B 站轨道短缓存与换源快照
    private val biliTracksMutex = Mutex()
    private val biliTracksCache = ConcurrentHashMap<String, BiliTracksEntry>()
    private val snapshots = ConcurrentHashMap<String, ProbeSnapshot>()

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
        val settings = settingsProvider()
        val result = lockFor(bvid).withLock {
            resolveLocked(bvid, title, upper, durationSec, refresh)
        }
        // 本地 / 演示 / 带 pin 的来源没有完整探测阶梯，不能拿它当换源可用性快照；
        // timeout / error 是瞬时失败，也不该在 5 分钟内锁成「不可用」。
        val transient = result.status == LyricsResult.STATUS_NONE &&
            (result.reason == LyricsResult.REASON_TIMEOUT ||
                result.reason == LyricsResult.REASON_ERROR)
        val pinned = readPrefs(bvid).pinSource
        if (!transient && result.source != SOURCE_LOCAL && result.source != SOURCE_DEMO &&
            pinned == null
        ) {
            putSnapshot(
                bvid,
                ProbeSnapshot(
                    expiresAt = clock() + PROBE_SNAPSHOT_TTL_MS,
                    online = settings.lyricsOnline,
                    biliEnabled = settings.lyricsBili,
                    loggedIn = bili.loggedIn,
                    durationSec = durationSec,
                    biliKind = result.meta.available.bili,
                    lrclibMatched = result.meta.matched,
                ),
            )
        }
        return applyOffsetSource(result, readPrefs(bvid))
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
        val localAvailable = readLocal(bvid) != null
        if (!demo) {
            val snapshot = snapshots[bvid]
            if (snapshot != null && snapshot.expiresAt > clock() &&
                snapshot.online == settings.lyricsOnline &&
                snapshot.biliEnabled == settings.lyricsBili &&
                snapshot.loggedIn == bili.loggedIn
            ) {
                return sourceItems(localAvailable, snapshot.biliKind, snapshot.lrclibMatched, demo = false)
            }
        }

        val probe = Probe(bvid)
        var biliKind = ""
        if (!demo && settings.lyricsBili) {
            runCatching {
                val tracks = probe.subtitleTracks()
                val manual = tracks?.firstOrNull { !it.isAi }
                val ai = tracks?.firstOrNull { it.isAi && it.lan == "ai-zh" }
                    ?: tracks?.firstOrNull { it.isAi }
                biliKind = when {
                    manual != null -> "manual"
                    ai != null -> "ai"
                    else -> ""
                }
            }
        }

        var lrclibMatched: LyricsMatched? = null
        if (!demo && settings.lyricsOnline) {
            runCatching {
                val effectiveDuration = if (durationSec > 0) durationSec else probe.pageDurationSec
                val found = findLrclib(probe, title, upper, effectiveDuration).firstOrNull()
                    ?: return@runCatching
                val (record, _) = found
                lrclibMatched = LyricsMatched(
                    id = record.id,
                    track = record.track,
                    artist = record.artist,
                    duration = record.duration,
                )
            }
        }
        putSnapshot(
            bvid,
            ProbeSnapshot(
                expiresAt = clock() + PROBE_SNAPSHOT_TTL_MS,
                online = settings.lyricsOnline,
                biliEnabled = settings.lyricsBili,
                loggedIn = bili.loggedIn,
                durationSec = durationSec,
                biliKind = biliKind,
                lrclibMatched = lrclibMatched,
            ),
        )
        return sourceItems(localAvailable, biliKind, lrclibMatched, demo)
    }

    private fun putSnapshot(bvid: String, snapshot: ProbeSnapshot) {
        snapshots[bvid] = snapshot
        if (snapshots.size > SNAPSHOT_CACHE_MAX) {
            snapshots.keys.firstOrNull()?.let { snapshots.remove(it) }
        }
    }

    private fun sourceItems(        localAvailable: Boolean,
        biliKind: String,
        lrclibMatched: LyricsMatched?,
        demo: Boolean,
    ): List<LyricsSourceOption> = listOf(
        LyricsSourceOption(
            source = SOURCE_LOCAL,
            available = localAvailable,
            label = LABEL_LOCAL,
        ),
        LyricsSourceOption(
            source = SOURCE_BILI,
            available = biliKind.isNotEmpty(),
            label = when (biliKind) {
                "ai" -> LABEL_BILI_AI
                "manual" -> LABEL_BILI_MANUAL
                // 不可用时给中性标签：不能谎称「人工」字幕。
                else -> LABEL_BILI
            },
            kind = biliKind,
        ),
        LyricsSourceOption(
            source = SOURCE_LRCLIB,
            available = lrclibMatched != null,
            label = LABEL_LRCLIB,
            matched = lrclibMatched,
        ),
        LyricsSourceOption(
            source = SOURCE_DEMO,
            available = demo,
            label = LABEL_DEMO,
        ),
    )

    /** LRCLIB 搜索（搜索面板），最多 10 条；关闭在线来源时直接返回空。 */
    suspend fun search(query: String): List<LyricsCandidate> {
        val keyword = query.trim()
        if (keyword.isEmpty() || !settingsProvider().lyricsOnline) return emptyList()
        val records = cachedSearch(
            params = mapOf("q" to keyword),
            probe = Probe(""),
        ) ?: return emptyList()
        return records.take(MAX_SEARCH_RESULTS).map { it.toCandidate() }
    }

    /** 采用某条 LRCLIB 候选并锁定（pin），之后解析优先用这条 id。 */
    suspend fun useCandidate(bvid: String, id: Long): LyricsResult {
        if (!VALID_BVID.matches(bvid)) return LyricsResult.none(LyricsResult.REASON_ERROR)
        val settings = settingsProvider()
        if (!settings.lyricsOnline) {
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
                val base = lrclibResult(record, prefs.offsetMs)
                if (base == null) {
                    LyricsResult.none(LyricsResult.REASON_NOT_FOUND, prefs.offsetMs)
                } else {
                    val subtitle = if (settings.lyricsBili && base.synced) {
                        probe.biliLines(ai = false) ?: probe.biliLines(ai = true)
                    } else {
                        null
                    }
                    val (calibrated, updated) = calibrate(bvid, base, prefs, record, subtitle)
                    val pinned = updated.copy(pinSource = SOURCE_LRCLIB, pinId = id)
                    writePrefs(bvid, pinned)
                    val result = applyOffsetSource(calibrated, pinned)
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
            applyOffsetSource(
                LyricsResult(
                    status = LyricsResult.STATUS_OK,
                    source = SOURCE_LOCAL,
                    sourceLabel = LABEL_LOCAL,
                    synced = parsed.synced,
                    offsetMs = prefs.offsetMs,
                    lines = parsed.lines,
                    meta = LyricsMeta(available = LyricsAvailable(local = true)),
                ),
                prefs,
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
     * 手动调偏即进入 `manual`；调回 0 则允许下次重新自动校准。
     */
    fun setOffset(bvid: String, offsetMs: Long) {
        if (!VALID_BVID.matches(bvid)) return
        val prefs = readPrefs(bvid)
        val clamped = clampOffset(offsetMs)
        writePrefs(
            bvid,
            prefs.copy(
                offsetMs = clamped,
                offsetMode = if (clamped != 0L) OFFSET_MODE_MANUAL else OFFSET_MODE_AUTO,
            ),
        )
    }

    /** 恢复自动校准：偏移归零并允许下一次解析重新估计。 */
    fun setOffsetModeAuto(bvid: String) {
        if (!VALID_BVID.matches(bvid)) return
        val prefs = readPrefs(bvid)
        writePrefs(bvid, prefs.copy(offsetMs = 0L, offsetMode = OFFSET_MODE_AUTO))
    }

    /** 读取某首歌的偏移 / 锁定偏好（界面或测试用）。 */
    fun preferences(bvid: String): LyricsPrefs = readPrefs(bvid)

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
        var prefs = readPrefs(bvid)

        // 1. 本地 LRC：文件存在即最高优先（演示曲目也一样）。
        readLocal(bvid)?.let { parsed ->
            return applyOffsetSource(
                LyricsResult(
                    status = LyricsResult.STATUS_OK,
                    source = SOURCE_LOCAL,
                    sourceLabel = LABEL_LOCAL,
                    synced = parsed.synced,
                    offsetMs = prefs.offsetMs,
                    lines = parsed.lines,
                    meta = LyricsMeta(available = LyricsAvailable(local = true)),
                ),
                prefs,
            )
        }

        // 演示曲目没有本地文件时给演示歌词，且不联网。
        if (isDemoBvid(bvid)) return applyOffsetSource(demoResult(prefs.offsetMs), prefs)

        val settings = settingsProvider()

        // 缓存只存抓取结果；偏移始终以当前偏好的最新值为准，避免调完偏移又跳回旧值。
        // 有 pin 时绝不读缓存：否则上一次自动阶梯的命中会遮蔽用户锁定的来源。
        if (!refresh && prefs.pinSource == null) {
            readCache(bvid)
                ?.takeIf { isCacheUsable(it, settings) }
                ?.let { cached ->
                    var offset = prefs.offsetMs
                    if (prefs.offsetMode == OFFSET_MODE_AUTO && offset == 0L) {
                        cached.meta.autoOffsetMs?.let { auto ->
                            offset = clampOffset(auto)
                            prefs = prefs.copy(
                                offsetMs = offset,
                                offsetMode = OFFSET_MODE_AUTO,
                            )
                            writePrefs(bvid, prefs)
                        }
                    }
                    return applyOffsetSource(cached.copy(offsetMs = offset), prefs)
                }
        }

        val probe = Probe(bvid)

        // 2. 用户锁定的来源（pin）。
        if (prefs.pinSource != null) {
            resolvePinned(bvid, prefs, upper, durationSec, settings, probe)?.let { pinned ->
                return cacheResult(bvid, prefs, applyOffsetSource(pinned, prefs))
            }
        }

        // 3. B 站人工字幕（lan 不以 ai- 开头）。
        if (settings.lyricsBili) {
            biliResult(probe, prefs, ai = false)?.let {
                return cacheResult(bvid, prefs, applyOffsetSource(it, prefs))
            }
        }

        // 4. LRCLIB：得分达标才用；AI 字幕与搜索并行预取，命中后用于自动校准。
        if (settings.lyricsOnline) {
            val (ranked, subtitle) = coroutineScope {
                val aiDeferred = if (settings.lyricsBili) {
                    async(Dispatchers.IO) { probe.biliLines(ai = true) }
                } else {
                    null
                }
                val ranked = try {
                    findLrclib(probe, title, upper, durationSec, refresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                val subtitle = aiDeferred?.let { runCatching { it.await() }.getOrNull() }
                ranked to subtitle
            }
            for ((record, _) in ranked.take(3)) {
                val base = lrclibResult(record, prefs.offsetMs) ?: continue
                val (calibrated, updated) = calibrate(bvid, base, prefs, record, subtitle)
                prefs = updated
                val result = calibrated.copy(
                    meta = calibrated.meta.copy(
                        available = LyricsAvailable(
                            local = false,
                            bili = probe.biliKind,
                            lrclib = true,
                        ),
                    ),
                )
                return cacheResult(bvid, prefs, applyOffsetSource(result, prefs))
            }
        }

        // 5. B 站 AI 字幕（过滤占位内容后至少 4 行有效文本）。
        if (settings.lyricsBili) {
            biliResult(probe, prefs, ai = true)?.let {
                return cacheResult(bvid, prefs, applyOffsetSource(it, prefs))
            }
        }

        // 6. none：按失败原因分类，交给界面显示不同空状态。
        val reason = when {
            !settings.lyricsOnline && !settings.lyricsBili -> LyricsResult.REASON_DISABLED
            probe.timeout -> LyricsResult.REASON_TIMEOUT
            probe.error -> LyricsResult.REASON_ERROR
            probe.notLoggedIn -> LyricsResult.REASON_NOT_LOGGED_IN
            else -> LyricsResult.REASON_NOT_FOUND
        }
        val none = applyOffsetSource(LyricsResult.none(reason, prefs.offsetMs), prefs)
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
                    coroutineScope {
                        // 字幕探测与 pin 记录并行，避免为校准多等 2-3 个串行往返
                        val recordDeferred = async(Dispatchers.IO) {
                            fetchRecordById(id, probe)
                        }
                        val subtitleDeferred = if (settings.lyricsBili) {
                            async(Dispatchers.IO) {
                                probe.biliLines(ai = false) ?: probe.biliLines(ai = true)
                            }
                        } else {
                            null
                        }
                        val record = recordDeferred.await()
                        if (record == null) {
                            subtitleDeferred?.cancel()
                            return@coroutineScope null
                        }
                        val base = lrclibResult(record, prefs.offsetMs)
                        if (base == null || !base.synced || subtitleDeferred == null) {
                            subtitleDeferred?.cancel()
                            return@coroutineScope base
                        }
                        val subtitle = runCatching { subtitleDeferred.await() }.getOrNull()
                        val (calibrated, updated) =
                            calibrate(bvid, base, prefs, record, subtitle)
                        if (updated != prefs) writePrefs(bvid, updated)
                        calibrated
                    }
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
     * 并行执行 LRCLIB 查询计划，合并去重后全局排序；返回可依次尝试的候选列表。
     * 结果与 Python `_search_lrclib` 一致。
     */
    private suspend fun findLrclib(
        probe: Probe,
        title: String,
        upper: String,
        durationSec: Int,
        refresh: Boolean = false,
    ): List<Pair<LrclibRecord, LyricsMatch.MatchScore>> {
        val queries = LyricsMatch.candidates(title, upper)
        if (queries.isEmpty()) return emptyList()
        val effectiveDuration = if (durationSec > 0) durationSec else probe.pageDurationSec
        val plan = LyricsMatch.queryPlan(queries, LyricsMatch.cleanTitle(title))
        if (plan.isEmpty()) return emptyList()

        val collected = coroutineScope {
            plan.map { params ->
                async(Dispatchers.IO) { cachedSearch(params, probe, refresh) }
            }.awaitAll()
                .flatMap { it ?: emptyList() }
        }
        val merged = LinkedHashMap<Long, LrclibRecord>()
        for (record in collected) {
            val existing = merged[record.id]
            merged[record.id] = if (existing == null) record else mergeRecord(existing, record)
        }
        val records = merged.values.toList()
        val ranked = LyricsMatch.rank(
            candidates = records.map { it.toCandidate() },
            queries = queries,
            upper = upper,
            videoDurationSec = effectiveDuration,
        )
        val byId = records.associateBy { it.id }
        return ranked.mapNotNull { (candidate, score) ->
            byId[candidate.id]?.let { it to score }
        }
    }

    /** 单条 LRCLIB 搜索：内存缓存 + 同参数在途合并，正结果 1h、空结果 10min。 */
    private suspend fun cachedSearch(
        params: Map<String, String>,
        probe: Probe,
        refresh: Boolean = false,
    ): List<LrclibRecord>? {
        val key = params.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        if (!refresh) {
            synchronized(searchCacheLock) {
                val entry = searchCache[key]
                if (entry != null) {
                    if (entry.expiresAt > clock()) return entry.records
                    searchCache.remove(key)
                }
            }
        }
        var task = searchInflight[key]
        if (task == null) {
            val created = searchScope.async { searchLrclib(params, probe) }
            val existing = searchInflight.putIfAbsent(key, created)
            if (existing != null) {
                // 另一个调用方刚好先注册了同一查询，取消自己这份
                created.cancel()
                task = existing
            } else {
                created.invokeOnCompletion { searchInflight.remove(key, created) }
                task = created
            }
        }
        // 调用方被取消时不连带取消共享查询；任务自己负责清理在途表
        val records = task.await()
        if (records != null) {
            val ttl = if (records.isEmpty()) SEARCH_MISS_TTL_MS else SEARCH_TTL_MS
            synchronized(searchCacheLock) {
                searchCache[key] = SearchCacheEntry(clock() + ttl, records)
                while (searchCache.size > SEARCH_CACHE_MAX) {
                    val oldest = searchCache.keys.firstOrNull() ?: break
                    searchCache.remove(oldest)
                }
            }
        }
        return records
    }

    /** 同一 id 被多个查询返回时，优先保留真正带歌词的那一份（与 Python `_merge_record` 一致）。 */
    private fun mergeRecord(existing: LrclibRecord, incoming: LrclibRecord): LrclibRecord =
        when {
            existing.syncedLyrics == null && incoming.syncedLyrics != null -> incoming
            existing.plainLyrics == null && incoming.plainLyrics != null -> incoming
            else -> existing
        }

    private suspend fun searchLrclib(
        params: Map<String, String>,
        probe: Probe,
    ): List<LrclibRecord>? {
        lrclibSemaphore.acquire()
        try {
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
        } finally {
            lrclibSemaphore.release()
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
                        val body = response.body
                        if (body == null) {
                            probe.error = true
                            return@use Fetch.Failed()
                        }
                        val source = body.source()
                        // 与 Python 一致：LRCLIB / 字幕响应上限 4 MB
                        if (source.request(MAX_REMOTE_BYTES.toLong() + 1)) {
                            probe.error = true
                            return@use Fetch.Failed()
                        }
                        val text = source.readUtf8()
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

    /** B 站 page_info + 字幕轨的短缓存（换源面板紧跟解析时不再重复请求）。 */
    private suspend fun loadBiliTracks(bvid: String): Pair<List<SubtitleTrack>, Int> {
        biliTracksCache[bvid]?.takeIf { it.expiresAt > clock() }?.let {
            return it.tracks to it.pageDurationSec
        }
        return biliTracksMutex.withLock {
            biliTracksCache[bvid]?.takeIf { it.expiresAt > clock() }?.let {
                return@withLock it.tracks to it.pageDurationSec
            }
            val page = bili.pageInfo(bvid)
            val tracks = if (page.cid <= 0) emptyList() else bili.subtitleTracks(bvid, page.cid)
            biliTracksCache[bvid] = BiliTracksEntry(
                expiresAt = clock() + BILI_TRACKS_TTL_MS,
                tracks = tracks,
                pageDurationSec = page.duration,
            )
            if (biliTracksCache.size > BILI_CACHE_MAX) {
                biliTracksCache.keys.firstOrNull()?.let { biliTracksCache.remove(it) }
            }
            tracks to page.duration
        }
    }

    /** 一次解析里的 B 站探测状态：字幕轨只拉一次，失败只记原因不抛。 */
    private inner class Probe(private val bvid: String) {
        @Volatile var timeout = false
        @Volatile var error = false
        @Volatile var notLoggedIn = false
        @Volatile var biliKind = ""
        @Volatile var pageDurationSec = 0
        private val tracksMutex = Mutex()
        private var tracksLoaded = false
        private var tracks: List<SubtitleTrack> = emptyList()
        private val linesMutex = Mutex()
        private var aiLoaded = false
        private var aiLines: List<LyricsLine>? = null

        suspend fun subtitleTracks(): List<SubtitleTrack>? = loadTracks()

        private suspend fun loadTracks(): List<SubtitleTrack>? = tracksMutex.withLock {
            if (tracksLoaded) return@withLock tracks
            tracksLoaded = true
            if (!bili.loggedIn) {
                notLoggedIn = true
                return@withLock null
            }
            try {
                val (loaded, duration) = loadBiliTracks(bvid)
                tracks = loaded
                pageDurationSec = duration
            } catch (e: SocketTimeoutException) {
                timeout = true
                return@withLock null
            } catch (e: IOException) {
                error = true
                return@withLock null
            } catch (e: Exception) {
                error = true
                return@withLock null
            }
            tracks
        }

        suspend fun biliLines(ai: Boolean): List<LyricsLine>? {
            if (!ai) return downloadLines(ai = false)
            // AI 字幕只下载一次：自动校准预取与第 5 步兜底共用
            return linesMutex.withLock {
                if (!aiLoaded) {
                    aiLines = downloadLines(ai = true)
                    aiLoaded = true
                }
                aiLines
            }
        }

        private suspend fun downloadLines(ai: Boolean): List<LyricsLine>? {
            val list = loadTracks() ?: return null
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

    /** 按当前偏好写 `offsetSource`；与 Python `_apply_offset_source` 语义一致。 */
    private fun applyOffsetSource(result: LyricsResult, prefs: LyricsPrefs): LyricsResult {
        val source = when {
            prefs.offsetMode == OFFSET_MODE_MANUAL -> OFFSET_MODE_MANUAL
            prefs.offsetMs != 0L -> OFFSET_MODE_AUTO
            else -> ""
        }
        return result.copy(offsetSource = source)
    }

    /**
     * 用本视频字幕行估计整首偏移；手动模式下只记录 `meta.autoOffsetMs`，不覆盖用户值。
     */
    private fun calibrate(
        bvid: String,
        result: LyricsResult,
        prefs: LyricsPrefs,
        record: LrclibRecord,
        subtitleLines: List<LyricsLine>?,
    ): Pair<LyricsResult, LyricsPrefs> {
        if (!result.synced || subtitleLines.isNullOrEmpty()) return result to prefs
        val refLines = LyricsLrc.parse(
            record.syncedLyrics ?: record.plainLyrics ?: "",
        ).lines
        if (refLines.isEmpty()) return result to prefs
        val estimate = LyricsOffset.estimate(
            refLines.map { it.timeMs / 1000.0 to it.text },
            subtitleLines.map { it.timeMs / 1000.0 to it.text },
        ) ?: return result to prefs
        val autoMs = clampOffset((estimate.offset * 1000).roundToLong())
        val meta = result.meta.copy(
            autoOffsetMs = autoMs,
            autoOffsetConfidence = estimate.confidence,
        )
        if (prefs.offsetMode == OFFSET_MODE_MANUAL) {
            return result.copy(meta = meta) to prefs
        }
        val updated = prefs.copy(offsetMs = autoMs, offsetMode = OFFSET_MODE_AUTO)
        writePrefs(bvid, updated)
        return result.copy(offsetMs = updated.offsetMs, meta = meta) to updated
    }

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
        if (!file.isFile) return LyricsPrefs(offsetMode = OFFSET_MODE_AUTO)
        return runCatching {
            val prefs = json.decodeFromString(LyricsPrefs.serializer(), file.readText(Charsets.UTF_8))
            if (prefs.v != PREFS_VERSION) {
                LyricsPrefs(offsetMode = OFFSET_MODE_AUTO)
            } else {
                // 旧文件没有 offsetMode：非零偏移按 manual 迁移，零偏移允许自动校准
                val mode = when (prefs.offsetMode) {
                    OFFSET_MODE_AUTO, OFFSET_MODE_MANUAL -> prefs.offsetMode
                    else -> if (prefs.offsetMs != 0L) OFFSET_MODE_MANUAL else OFFSET_MODE_AUTO
                }
                prefs.copy(offsetMs = clampOffset(prefs.offsetMs), offsetMode = mode)
            }
        }.getOrElse { LyricsPrefs(offsetMode = OFFSET_MODE_AUTO) }
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
            hasLyrics = syncedLyrics != null || plainLyrics != null,
        )
    }

    /** 抓取缓存文件：带结构版本与写入时间。 */
    @Serializable
    private data class LyricsCacheFile(
        val v: Int = CACHE_VERSION,
        val cachedAt: Long = 0L,
        val result: LyricsResult,
    )

    private data class SearchCacheEntry(
        val expiresAt: Long,
        val records: List<LrclibRecord>,
    )

    private data class BiliTracksEntry(
        val expiresAt: Long,
        val tracks: List<SubtitleTrack>,
        val pageDurationSec: Int,
    )

    private data class ProbeSnapshot(
        val expiresAt: Long,
        val online: Boolean,
        val biliEnabled: Boolean,
        val loggedIn: Boolean,
        val durationSec: Int,
        val biliKind: String,
        val lrclibMatched: LyricsMatched?,
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

private const val CACHE_VERSION = 2
private const val PREFS_VERSION = 1
private const val OK_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
private const val NONE_CACHE_TTL_MS = 6L * 60 * 60 * 1000
private const val MIN_AI_LINES = 4
private const val MAX_LOCKS = 256
private const val AI_MERGE_GAP_MS = 300L
private const val AI_MERGE_MAX_CHARS = 24
private const val AI_MERGE_MAX_SPAN_MS = 8_000L

/** LRCLIB 查询与缓存参数（与 Python 一致）。 */
private const val LRCLIB_CONCURRENCY = 4
private const val SEARCH_TTL_MS = 3_600_000L
private const val SEARCH_MISS_TTL_MS = 600_000L
private const val SEARCH_CACHE_MAX = 128
private const val BILI_TRACKS_TTL_MS = 60_000L
private const val PROBE_SNAPSHOT_TTL_MS = 300_000L
private const val BILI_CACHE_MAX = 128
private const val SNAPSHOT_CACHE_MAX = 256
private const val MAX_REMOTE_BYTES = 4 * 1024 * 1024

/** 偏移来源：系统自动校准 / 用户手动。 */
private const val OFFSET_MODE_AUTO = "auto"
private const val OFFSET_MODE_MANUAL = "manual"

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
