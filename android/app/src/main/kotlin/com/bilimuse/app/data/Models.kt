package com.bilimuse.app.data

import kotlinx.serialization.Serializable

@Serializable
data class SessionData(
    val cookies: Map<String, String> = emptyMap(),
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    val vip: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val loggedIn: Boolean
        get() = cookies["SESSDATA"].orEmpty().isNotEmpty() && mid > 0

    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun public(): UserInfo = UserInfo(
        loggedIn = loggedIn,
        mid = mid,
        uname = uname,
        face = face,
        vip = vip,
    )
}

data class UserInfo(
    val loggedIn: Boolean = false,
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    val vip: Boolean = false,
)

data class Folder(
    val id: Long,
    val title: String,
    val count: Int,
    val favState: Int = 0,
)

data class TrackItem(
    val bvid: String,
    val title: String,
    val cover: String = "",
    val upper: String = "",
    val duration: Int = 0,
    val intro: String = "",
    val ctime: Long = 0L,
)

data class FolderPage(
    val title: String = "",
    val items: List<TrackItem> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = 0,
)

data class AudioStream(
    val url: String,
    val backups: List<String>,
    val bandwidth: Int,
    val codecs: String,
    val mime: String,
    val quality: String,
)

data class TrackInfo(
    val bvid: String,
    val title: String,
    val size: Long,
    val quality: String,
    val bandwidth: Int,
    val codecs: String,
    val mime: String,
    val duration: Int,
    val mode: String,
    val nodes: Int,
)

data class QrInfo(val key: String, val url: String)

data class QrPollResult(
    val status: String,
    val message: String,
    val user: UserInfo? = null,
)

data class EngineSettings(
    val btrEnabled: Boolean = true,
    val concurrency: Int = 8,
    val chunkSize: Int = 512 * 1024,
    val preferLossless: Boolean = true,
    val showBtrDetails: Boolean = true,
) {
    fun clamp(): EngineSettings = copy(
        concurrency = concurrency.coerceIn(1, 64),
        chunkSize = chunkSize.coerceIn(64 * 1024, 8 * 1024 * 1024),
    )
}

open class BiliException(
    val code: Int,
    override val message: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
) : java.io.IOException(message)

class NotLoggedInException(message: String = "账号未登录") : BiliException(-101, message)

interface SessionSink {
    fun load(): SessionData
    fun save(data: SessionData)
    fun clear()
}
