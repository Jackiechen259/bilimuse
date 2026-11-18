package com.bilimuse.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * B 站 Web API：扫码登录、收藏夹、playurl（含 WBI 签名）。
 *
 * Cookie 由本类自己维护（等价于 Python 版 httpx 的 cookie jar），
 * 二维码回调 URL 里的 cookie 优先于 Set-Cookie。
 */
class BiliClient(
    private val sessionSink: SessionSink,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    /** API 根地址；JVM 单测里指向 MockWebServer，生产环境用 [API]。 */
    private val apiBase: String = API,
) : LyricsBiliSource {
    private val lock = Any()
    private val cookies = LinkedHashMap<String, String>()
    private var current: SessionData = sessionSink.load()

    init {
        synchronized(lock) { cookies.putAll(current.cookies) }
    }

    private val client = httpClient.newBuilder().followRedirects(true).build()

    /**
     * 字幕下载专用客户端：**不跟随重定向**。
     *
     * 共享客户端是 `followRedirects(true)`，只校验首跳 URL 时，白名单域可以 302 到
     * 任意地址（例如云主机元数据接口），等于把请求变成代理。任何 3xx 一律拒绝。
     */
    private val subtitleClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val session: SessionData
        get() = synchronized(lock) { current }

    override val loggedIn: Boolean
        get() = synchronized(lock) {
            cookies["SESSDATA"].orEmpty().isNotEmpty() && current.mid > 0
        }

    // ---------------------------------------------------------------- 基础请求

    private fun cookieHeader(): String = synchronized(lock) {
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun cookieSnapshot(): Map<String, String> = synchronized(lock) {
        LinkedHashMap(cookies)
    }

    private fun putCookie(name: String, value: String) {
        if (name.isBlank() || value.isBlank()) return
        synchronized(lock) { cookies[name] = value }
    }

    private fun hasCookie(name: String): Boolean = synchronized(lock) {
        cookies.containsKey(name)
    }

    private fun captureCookies(response: Response) {
        for (header in response.headers("Set-Cookie")) {
            val pair = header.substringBefore(';').trim()
            val equals = pair.indexOf('=')
            if (equals <= 0) continue
            putCookie(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim())
        }
    }

    private suspend fun requestData(
        url: String,
        params: Map<String, String> = emptyMap(),
        needLogin: Boolean = false,
    ): JsonElement = withContext(Dispatchers.IO) {
        if (needLogin && !loggedIn) throw NotLoggedInException()
        val httpUrl = url.toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val builder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "application/json, text/plain, */*")
            .get()
        cookieHeader().takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }

        val call = client.newCall(builder.build())
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (e is BiliException) throw e
            throw BiliException(-1, "网络连接失败，请检查网络后重试")
        }
        response.use { response ->
            captureCookies(response)
            if (!response.isSuccessful) {
                throw BiliException(-1, "HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                ?: throw BiliException(-1, "B 站返回了无法解析的数据")
            val obj = root as? JsonObject
                ?: throw BiliException(-1, "B 站返回了非预期数据")
            val code = obj["code"].asIntOrNull() ?: 0
            if (code != 0) {
                throw BiliException(
                    code,
                    obj["message"].asStringOrNull() ?: "接口返回错误",
                    obj["data"],
                )
            }
            obj["data"] ?: JsonNull
        }
    }

    // ---------------------------------------------------------------- 登录态

    suspend fun state(): UserInfo {
        if (!loggedIn) return session.public()
        return try {
            refreshProfile()
        } catch (e: NotLoggedInException) {
            logout()
            UserInfo()
        } catch (e: IOException) {
            session.public()
        }
    }

    suspend fun refreshProfile(): UserInfo {
        val data = requestData("$apiBase/x/web-interface/nav").asObjectOrNull()
            ?: JsonObject(emptyMap())
        if (data["isLogin"].asBooleanOrNull() != true) throw NotLoggedInException()
        val vip = data["vipStatus"].asIntOrNull() == 1 ||
            data["vip"].asObjectOrNull()?.get("status").asBooleanOrNull() == true
        val updated = session.copy(
            mid = data["mid"].asLongOrNull() ?: session.mid,
            uname = data["uname"].asStringOrNull().orEmpty(),
            face = httpsUrl(data["face"].asStringOrNull().orEmpty()),
            vip = vip,
            cookies = cookieSnapshot(),
            updatedAt = System.currentTimeMillis() / 1000,
        )
        synchronized(lock) { current = updated }
        sessionSink.save(updated)
        return updated.public()
    }

    /**
     * 用 WebView 官方登录页拿到的 cookie 完成登录。
     *
     * B 站密码 / 短信登录都必须先过人机验证（极验），原生实现需要嵌入整套
     * 验证码 SDK；这里让官方页在 WebView 里完成登录，再把 cookie 换进原生会话。
     * 先临时替换 cookie → nav 校验 → 成功持久化；失败恢复原来的登录态。
     */
    suspend fun completeLoginFromCookies(newCookies: Map<String, String>): UserInfo {
        val previousCookies: Map<String, String>
        val previousSession: SessionData
        synchronized(lock) {
            previousCookies = LinkedHashMap(cookies)
            previousSession = current
            cookies.clear()
            newCookies.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) cookies[name] = value
            }
            current = SessionData(cookies = cookieSnapshot())
        }
        return try {
            val user = refreshProfile()
            sessionSink.save(session)
            user
        } catch (e: Exception) {
            synchronized(lock) {
                cookies.clear()
                cookies.putAll(previousCookies)
                current = previousSession
            }
            throw e
        }
    }

    fun logout() {
        synchronized(lock) {
            cookies.clear()
            current = SessionData()
        }
        sessionSink.clear()
    }

    // ---------------------------------------------------------------- 扫码登录

    suspend fun qrGenerate(): QrInfo {
        val data = requestData("$PASSPORT/x/passport-login/web/qrcode/generate").asObjectOrNull()
            ?: throw BiliException(-1, "二维码生成失败")
        val key = data["qrcode_key"].asStringOrNull().orEmpty()
        val url = data["url"].asStringOrNull().orEmpty()
        if (key.isEmpty() || url.isEmpty()) throw BiliException(-1, "二维码生成失败")
        return QrInfo(key, url)
    }

    suspend fun qrPoll(key: String): QrPollResult {
        val data = requestData(
            "$PASSPORT/x/passport-login/web/qrcode/poll",
            mapOf("qrcode_key" to key, "source" to "main-mini"),
        ).asObjectOrNull() ?: return QrPollResult("pending", "等待扫码")
        val code = data["code"].asIntOrNull() ?: -1
        when (code) {
            86101 -> return QrPollResult("pending", "等待扫码")
            86090 -> return QrPollResult("scanned", "已扫码，请在手机上确认")
            86038 -> return QrPollResult("expired", "二维码已过期")
            0 -> Unit
            else -> return QrPollResult(
                "pending",
                data["message"].asStringOrNull() ?: "等待扫码",
            )
        }

        // 回调地址上的 query 参数最可靠；Set-Cookie 已经在上面的请求里收下了
        val callback = data["url"].asStringOrNull().orEmpty()
        callback.toHttpUrlOrNull()?.let { parsed ->
            for (name in listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")) {
                parsed.queryParameter(name)?.takeIf { it.isNotEmpty() }?.let {
                    putCookie(name, it)
                }
            }
        }
        if (!hasCookie("SESSDATA")) {
            throw BiliException(-1, "登录成功但没拿到 SESSDATA")
        }
        val mid = cookieSnapshot()["DedeUserID"]?.toLongOrNull() ?: 0L
        synchronized(lock) {
            current = current.copy(
                cookies = cookieSnapshot(),
                mid = mid,
                updatedAt = System.currentTimeMillis() / 1000,
            )
        }
        val user = try {
            refreshProfile()
        } catch (e: NotLoggedInException) {
            throw e
        } catch (e: IOException) {
            session.public()
        }
        sessionSink.save(session)
        return QrPollResult("success", "登录成功", user)
    }

    // ---------------------------------------------------------------- 收藏夹

    suspend fun folders(): List<Folder> {
        if (session.mid <= 0L) refreshProfile()
        val mid = session.mid
        val data = requestData(
            "$apiBase/x/v3/fav/folder/created/list-all",
            mapOf("up_mid" to mid.toString(), "web_location" to "333.1387"),
            needLogin = true,
        ).asObjectOrNull()
        val list = data?.get("list").asArrayOrNull() ?: return emptyList()
        return list.mapNotNull { element ->
            val folder = element.asObjectOrNull() ?: return@mapNotNull null
            Folder(
                id = folder["id"].asLongOrNull() ?: 0L,
                title = folder["title"].asStringOrNull() ?: "未命名收藏夹",
                count = folder["media_count"].asIntOrNull() ?: 0,
                favState = folder["fav_state"].asIntOrNull() ?: 0,
            )
        }
    }

    suspend fun folderItems(
        mediaId: Long,
        page: Int = 1,
        size: Int = 30,
        keyword: String = "",
        title: String = "",
    ): FolderPage {
        val params = linkedMapOf(
            "media_id" to mediaId.toString(),
            "pn" to page.toString(),
            "ps" to size.toString(),
            "order" to "mtime",
            "type" to "0",
            "tid" to "0",
            "platform" to "web",
            "web_location" to "333.1387",
        )
        if (keyword.isNotEmpty()) params["keyword"] = keyword
        val data = requestData(
            "$apiBase/x/v3/fav/resource/list",
            params,
            needLogin = true,
        ).asObjectOrNull() ?: return FolderPage(title = title)
        val medias = data["medias"].asArrayOrNull() ?: JsonArray(emptyList())
        val items = medias.mapNotNull { element ->
            val media = element.asObjectOrNull() ?: return@mapNotNull null
            val bvid = media["bvid"].asStringOrNull().orEmpty()
            val attr = media["attr"].asIntOrNull() ?: 0
            // 过滤已失效的稿件
            if (bvid.isEmpty() || (attr and 9) != 0) return@mapNotNull null
            TrackItem(
                bvid = bvid,
                title = media["title"].asStringOrNull().orEmpty(),
                cover = httpsUrl(media["cover"].asStringOrNull().orEmpty()),
                upper = media["upper"].asObjectOrNull()
                    ?.get("name").asStringOrNull().orEmpty(),
                duration = media["duration"].asIntOrNull() ?: 0,
                ctime = media["ctime"].asLongOrNull() ?: 0L,
                intro = (media["intro"].asStringOrNull().orEmpty()).take(200),
            )
        }
        val total = data["info"].asObjectOrNull()
            ?.get("media_count").asIntOrNull() ?: 0
        return FolderPage(
            title = title,
            items = items,
            hasMore = data["has_more"].asBooleanOrNull() == true,
            total = total,
        )
    }

    // ---------------------------------------------------------------- 取流

    override suspend fun pageInfo(bvid: String): PageInfo {
        val data = requestData("$apiBase/x/player/pagelist", mapOf("bvid" to bvid))
        val pages = when (data) {
            is JsonArray -> data
            is JsonObject -> data["page"].asArrayOrNull()
            else -> null
        } ?: throw BiliException(-404, "找不到该视频的分P")
        val first = pages.firstOrNull().asObjectOrNull()
            ?: throw BiliException(-404, "找不到该视频的分P")
        return PageInfo(
            cid = first["cid"].asLongOrNull() ?: 0L,
            duration = first["duration"].asIntOrNull() ?: 0,
            part = first["part"].asStringOrNull().orEmpty(),
        )
    }

    suspend fun playurl(bvid: String, cid: Long): JsonObject {
        ensureBuvid()
        val params = linkedMapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to "4048",
            "fnver" to "0",
            "fourk" to "1",
            "try_look" to "1",
        )
        return try {
            val (img, sub) = wbiKeys()
            if (img.isEmpty() || sub.isEmpty()) throw BiliException(-1, "WBI 密钥不可用")
            val signed = Wbi.sign(params, img, sub)
            requestData("$apiBase/x/player/wbi/playurl", signed).asObjectOrNull()
                ?: JsonObject(emptyMap())
        } catch (e: IOException) {
            requestData("$apiBase/x/player/playurl", params).asObjectOrNull()
                ?: JsonObject(emptyMap())
        }
    }

    private suspend fun wbiKeys(): Pair<String, String> {
        val data = requestData("$apiBase/x/web-interface/nav").asObjectOrNull()
            ?: return "" to ""
        val wbi = data["wbi_img"].asObjectOrNull() ?: return "" to ""
        val img = wbi["img_url"].asStringOrNull().orEmpty()
            .substringAfterLast('/').substringBefore('.')
        val sub = wbi["sub_url"].asStringOrNull().orEmpty()
            .substringAfterLast('/').substringBefore('.')
        return img to sub
    }

    /** 部分接口要求 buvid3/buvid4，缺了就补一个。 */
    private suspend fun ensureBuvid() {
        if (hasCookie("buvid3")) return
        try {
            val data = requestData("$apiBase/x/frontend/finger/spi").asObjectOrNull()
            data?.get("b_3").asStringOrNull()?.let { putCookie("buvid3", it) }
            data?.get("b_4").asStringOrNull()?.let { putCookie("buvid4", it) }
        } catch (e: IOException) {
            // buvid 只是锦上添花，失败不影响主流程
        }
    }

    // ---------------------------------------------------------------- 字幕

    /**
     * 某个分P的字幕轨列表：人工字幕在前，其次 `ai-zh`，最后其它 AI 字幕。
     *
     * 接口报错 / 未登录一律抛 [BiliException]，由歌词仓库当作「这个源不可用」降级。
     * 返回的 `url` 已经过白名单校验并补全成绝对 https 地址。
     */
    override suspend fun subtitleTracks(bvid: String, cid: Long): List<SubtitleTrack> {
        val data = requestData(
            "$apiBase/x/player/v2",
            mapOf("bvid" to bvid, "cid" to cid.toString()),
        ).asObjectOrNull() ?: return emptyList()
        val subtitles = data["subtitle"].asObjectOrNull()
            ?.get("subtitles").asArrayOrNull()
            ?: return emptyList()
        val tracks = subtitles.mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val lan = item["lan"].asStringOrNull().orEmpty()
            val url = resolveSubtitleUrl(item["subtitle_url"].asStringOrNull().orEmpty())
            if (lan.isEmpty() || url == null) return@mapNotNull null
            SubtitleTrack(
                lan = lan,
                lanDoc = item["lan_doc"].asStringOrNull().orEmpty(),
                url = url,
                isAi = lan.startsWith("ai-"),
            )
        }
        return tracks.sortedWith(
            compareBy<SubtitleTrack>({ it.isAi }, { if (it.lan == "ai-zh") 0 else 1 }),
        )
    }

    /** 下载并解析字幕 JSON；URL 不在白名单内时直接拒绝，不发任何请求。 */
    override suspend fun subtitleBody(url: String): JsonObject {
        val resolved = resolveSubtitleUrl(url)
            ?: throw BiliException(-1, "字幕地址不受信任")
        return downloadSubtitle(resolved)
    }

    /**
     * 下载已通过白名单校验的字幕地址（单测直接调用以覆盖重定向 / 体积限制）。
     *
     * - 使用不跟随重定向的客户端：任何 3xx 都视为失败，避免白名单域把请求转发到内网；
     * - 响应体上限 [SUBTITLE_MAX_BYTES]（4 MB），超过就拒绝，避免被超大响应拖垮。
     */
    internal suspend fun downloadSubtitle(resolvedUrl: String): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .get()
                .build()
            val response = try {
                subtitleClient.newCall(request).execute()
            } catch (e: IOException) {
                throw BiliException(-1, "字幕下载失败，请检查网络后重试")
            }
            response.use { res ->
                val code = res.code
                if (code in 300..399) {
                    throw BiliException(-1, "字幕地址发生了重定向")
                }
                if (!res.isSuccessful) throw BiliException(-1, "HTTP $code")
                val body = res.body ?: throw BiliException(-1, "字幕数据为空")
                if (body.contentLength() > SUBTITLE_MAX_BYTES) {
                    throw BiliException(-1, "字幕数据超过 4 MB")
                }
                val text = readLimited(body.byteStream())
                Json.parseToJsonElement(text) as? JsonObject
                    ?: throw BiliException(-1, "字幕数据格式异常")
            }
        }

    private fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > SUBTITLE_MAX_BYTES) throw BiliException(-1, "字幕数据超过 4 MB")
                output.write(buffer, 0, read)
            }
        }
        return output.toString("UTF-8")
    }

    data class PageInfo(val cid: Long, val duration: Int, val part: String)

    companion object {
        const val API = "https://api.bilibili.com"
        const val PASSPORT = "https://passport.bilibili.com"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val REFERER = "https://www.bilibili.com"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun httpsUrl(url: String): String = when {
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            else -> url
        }

        /** 字幕 CDN 白名单后缀。 */
        private val SUBTITLE_HOST_SUFFIXES = listOf(".hdslb.com", ".bilibili.com", ".bilivideo.com")

        /** 字幕响应体上限（4 MB）：超过就拒绝，避免被超大响应拖垮。 */
        const val SUBTITLE_MAX_BYTES = 4L * 1024 * 1024

        /**
         * 字幕地址白名单：协议必须是 https，host 必须是 B 站自家域名；
         * `//host/path` 与 `/path` 这类相对地址按 `https://www.bilibili.com` 补全。
         *
         * 返回用解析后的 scheme/host 重建的绝对地址；不合法时返回 null（调用方直接拒绝，
         * 避免被当成代理）。显式拒绝 userinfo、反斜杠与非 ASCII host。
         */
        fun resolveSubtitleUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            // 反斜杠会被部分解析器当成 `/`，明确拒绝，避免 `https://i0.hdslb.com\@evil.com` 之类的歧义。
            if (trimmed.contains('\\')) return null
            val absolute = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) -> return null
                trimmed.startsWith("/") -> "$REFERER$trimmed"
                else -> "$REFERER/$trimmed"
            }
            val authority = absolute.substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            if (authority.isEmpty()) return null
            // 显式拒绝 userinfo：`https://i0.hdslb.com@evil.com/...` 的真实 host 是 evil.com。
            if (authority.contains('@')) return null
            // 显式拒绝非 ASCII host：IDN / 全角句点归一化可能把 `i0。hdslb.com` 变成白名单域。
            if (authority.any { it.code > 127 }) return null
            if (authority.any { it.isWhitespace() }) return null
            val parsed = absolute.toHttpUrlOrNull() ?: return null
            if (!parsed.scheme.equals("https", ignoreCase = true)) return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            val host = parsed.host.lowercase()
            val allowed = SUBTITLE_HOST_SUFFIXES.any { suffix ->
                host == suffix.removePrefix(".") || host.endsWith(suffix)
            }
            if (!allowed) return null
            // 用解析后的 scheme/host 重建 URL，丢掉原始字符串里可能夹带的东西。
            val builder = HttpUrl.Builder()
                .scheme("https")
                .host(parsed.host)
                .port(parsed.port)
                .encodedPath(parsed.encodedPath)
            parsed.encodedQuery?.let { builder.encodedQuery(it) }
            return builder.build().toString()
        }
    }
}

/** 歌词仓库需要的 B 站能力；抽成接口方便 JVM 单测替换实现。 */
interface LyricsBiliSource {
    val loggedIn: Boolean

    suspend fun pageInfo(bvid: String): BiliClient.PageInfo

    suspend fun subtitleTracks(bvid: String, cid: Long): List<SubtitleTrack>

    suspend fun subtitleBody(url: String): JsonObject
}

/** B 站字幕轨。[isAi] 由 `lan` 是否以 `ai-` 开头决定。 */
data class SubtitleTrack(
    val lan: String,
    val lanDoc: String,
    val url: String,
    val isAi: Boolean,
)

// ---------------------------------------------------------------- JSON 小工具

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

internal fun JsonElement?.asLongOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

internal fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

// ---------------------------------------------------------------- 音轨选择

private val AUDIO_QUALITY = mapOf(
    30216 to "64K",
    30232 to "132K",
    30280 to "192K",
    30250 to "杜比全景声",
    30251 to "Hi-Res 无损",
)

/** 从 dash 里挑一条最适合当音乐听的音轨：Hi-Res > 杜比 > 最高码率。 */
fun pickAudio(dash: JsonObject, preferLossless: Boolean = true): AudioStream {
    data class Candidate(val stream: AudioStream, val lossless: Boolean)

    val candidates = mutableListOf<Candidate>()

    fun add(entry: JsonElement?, quality: String) {
        val obj = entry.asObjectOrNull() ?: return
        val url = obj["baseUrl"].asStringOrNull() ?: obj["base_url"].asStringOrNull() ?: return
        if (url.isEmpty()) return
        val backups = (obj["backupUrl"].asArrayOrNull() ?: obj["backup_url"].asArrayOrNull())
            ?.mapNotNull { it.asStringOrNull() }
            ?.filter { it.isNotEmpty() }
            ?.map(BiliClient::httpsUrl)
            ?: emptyList()
        val lossless = quality == "Hi-Res 无损" || quality == "杜比全景声"
        candidates += Candidate(
            AudioStream(
                url = BiliClient.httpsUrl(url),
                backups = backups,
                bandwidth = obj["bandwidth"].asIntOrNull() ?: 0,
                codecs = obj["codecs"].asStringOrNull().orEmpty(),
                mime = obj["mimeType"].asStringOrNull()
                    ?: obj["mime_type"].asStringOrNull().orEmpty(),
                quality = quality,
            ),
            lossless,
        )
    }

    if (preferLossless) {
        add(dash["flac"].asObjectOrNull()?.get("audio"), "Hi-Res 无损")
        add(dash["dolby"].asObjectOrNull()?.get("audio"), "杜比全景声")
    }
    dash["audio"].asArrayOrNull()?.forEach { element ->
        val item = element.asObjectOrNull() ?: return@forEach
        val id = item["id"].asIntOrNull() ?: 0
        add(item, AUDIO_QUALITY[id] ?: id.toString())
    }

    if (candidates.isEmpty()) {
        throw BiliException(-404, "该视频没有可用的音频流（可能是番剧或需大会员）")
    }
    return candidates
        .sortedWith(compareBy<Candidate>({ if (it.lossless) 1 else 0 }, { it.stream.bandwidth }))
        .last()
        .stream
}
