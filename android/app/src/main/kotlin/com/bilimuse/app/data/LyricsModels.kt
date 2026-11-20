package com.bilimuse.app.data

import kotlinx.serialization.Serializable

/**
 * 歌词数据模型。
 *
 * 与 `docs/LYRICS.md` 的服务端结果体一一对应，只有一点本地化：服务端用秒（float）表示
 * 偏移，安卓内部统一用毫秒（[LyricsResult.offsetMs]），界面按 `t + offset` 使用。
 */

/** 一行歌词。[timeMs] 是原始歌词时间（毫秒），偏移由 [LyricsResult.offsetMs] 单独携带。 */
@Serializable
data class LyricsLine(
    val timeMs: Long,
    val text: String,
)

/** 解析结果：`ok` 有歌词，`none` 只有原因。 */
@Serializable
data class LyricsResult(
    val status: String = STATUS_NONE,
    /** `local` / `bili` / `lrclib` / `demo`；`none` 时为空。 */
    val source: String = "",
    val sourceLabel: String = "",
    val synced: Boolean = false,
    val offsetMs: Long = 0L,
    /** 当前偏移来源：`auto` / `manual` / 空串。 */
    val offsetSource: String = "",
    val lines: List<LyricsLine> = emptyList(),
    val meta: LyricsMeta = LyricsMeta(),
    /** `not_found` / `disabled` / `not_logged_in` / `timeout` / `error`。 */
    val reason: String = "",
) {
    val ok: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_NONE = "none"

        const val REASON_NOT_FOUND = "not_found"
        const val REASON_DISABLED = "disabled"
        const val REASON_NOT_LOGGED_IN = "not_logged_in"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_ERROR = "error"

        fun none(reason: String, offsetMs: Long = 0L): LyricsResult = LyricsResult(
            status = STATUS_NONE,
            offsetMs = offsetMs,
            reason = reason,
        )
    }
}

@Serializable
data class LyricsMeta(
    /** LRCLIB 匹配到的条目信息；本地 / B 站 / 演示来源为 null。 */
    val matched: LyricsMatched? = null,
    val available: LyricsAvailable = LyricsAvailable(),
    val cached: Boolean = false,
    /** 本次自动校准算出的偏移（毫秒）；未校准时为 null。 */
    val autoOffsetMs: Long? = null,
    val autoOffsetConfidence: Double? = null,
)

/** 匹配信息：选错歌时用户要能看出并自己改。 */
@Serializable
data class LyricsMatched(
    val id: Long = 0L,
    val track: String = "",
    val artist: String = "",
    val duration: Int = 0,
)

/** 各来源可用性快照。 */
@Serializable
data class LyricsAvailable(
    val local: Boolean = false,
    /** B 站字幕类型：空串表示不可用，`manual` 人工，`ai` AI 转写。 */
    val bili: String = "",
    val lrclib: Boolean = false,
)

/** LRCLIB 候选（搜索结果 / 换源菜单用）。 */
@Serializable
data class LyricsCandidate(
    val id: Long = 0L,
    val track: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val instrumental: Boolean = false,
    val synced: Boolean = false,
    /** 是否至少有 synced/plain 歌词；默认 true 兼容旧调用方。 */
    val hasLyrics: Boolean = true,
)

/** 换源菜单里的一项。 */
@Serializable
data class LyricsSourceOption(
    val source: String,
    val available: Boolean,
    val label: String,
    /** 仅 B 站字幕使用：`manual` / `ai`。 */
    val kind: String = "",
    val matched: LyricsMatched? = null,
)

/**
 * 按 bvid 持久化的用户偏好：偏移（毫秒）与锁定来源。
 *
 * 与网页版一致，只保存 offset 与 pin；服务端接口用秒，这里用毫秒，避免界面层反复换算。
 */
@Serializable
data class LyricsPrefs(
    val offsetMs: Long = 0L,
    /** `auto` 系统可覆盖；`manual` 用户手动值。空串表示旧文件，读取时迁移。 */
    val offsetMode: String = "",
    /** `local` / `bili` / `lrclib` / `demo`，null 表示未锁定。 */
    val pinSource: String? = null,
    /** 锁定到某条 LRCLIB 候选时使用。 */
    val pinId: Long? = null,
    /** 偏好文件结构版本；未来不兼容时直接丢弃。 */
    val v: Int = 1,
)
