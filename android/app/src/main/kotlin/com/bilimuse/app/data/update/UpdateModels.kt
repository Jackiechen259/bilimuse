package com.bilimuse.app.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * GitHub Releases 自动更新用到的纯数据模型与解析逻辑。
 *
 * 这里刻意不依赖 Android API：版本比较、Release 解析和 APK 资产选择都能在
 * 普通 JVM 单元测试里覆盖（见 `UpdateModelsTest`）。
 */

/** 语义化版本，忽略 build metadata（`+` 之后的部分）。 */
data class AppVersion(
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0,
    val prerelease: List<String> = emptyList(),
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return comparePrerelease(prerelease, other.prerelease)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (prerelease.isNotEmpty()) {
            append('-').append(prerelease.joinToString("."))
        }
    }

    companion object {
        private val PATTERN = Regex(
            """^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""",
        )

        /** 接受 `v1.2.3`、`1.2`、`1.2.3-beta.1`、`1.2.3+4` 等写法；无法解析时返回 null。 */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val match = PATTERN.matchEntire(text) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val patch = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val prerelease = match.groupValues[4]
                .takeIf { it.isNotEmpty() }
                ?.split('.')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            return AppVersion(major, minor, patch, prerelease)
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() && b.isEmpty()) return 0
            // 正式版优先级高于同号的预发布版。
            if (a.isEmpty()) return 1
            if (b.isEmpty()) return -1
            for (index in 0 until min(a.size, b.size)) {
                val left = a[index]
                val right = b[index]
                val leftNumber = left.toIntOrNull()
                val rightNumber = right.toIntOrNull()
                val result = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1 // 数字标识符优先级低于字母标识符
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (result != 0) return result
            }
            return a.size.compareTo(b.size)
        }
    }
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    /** GitHub 会自动计算资产摘要，格式如 `sha256:<hex>`；旧数据可能没有。 */
    val digest: String? = null,
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

/** 已确认可用的更新包。 */
data class ReleaseAsset(
    val name: String,
    val size: Long,
    val url: String,
    val sha256: String?,
)

/** 一次可安装的更新。 */
data class AppUpdate(
    val version: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String?,
    val asset: ReleaseAsset,
)

sealed interface UpdateCheckResult {
    data class Available(val update: AppUpdate) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    /** GitHub 上还没有发布过 Release（或只有草稿/预发布），不是错误。 */
    data object NoRelease : UpdateCheckResult
}

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val UPDATE_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun parseGitHubRelease(json: String): GitHubRelease = UPDATE_JSON.decodeFromString(json)

private val ABI_MARKERS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/**
 * 从 Release 资产里挑一个 APK：
 * - 明确标了 `universal` 的优先；
 * - 其次是没有 ABI 标记的（默认视为通用包，CI 产出的就是这种）；
 * - 再其次是标了当前设备 ABI 的分包；
 * - 标了其他 ABI 的分包直接排除，避免装错架构。
 */
fun selectApkAsset(assets: List<GitHubAsset>, supportedAbis: List<String>): GitHubAsset? {
    val supported = supportedAbis.map { it.lowercase() }
    val candidates = assets.mapNotNull { asset ->
        if (!asset.name.endsWith(".apk", ignoreCase = true) || asset.downloadUrl.isBlank()) {
            return@mapNotNull null
        }
        val name = asset.name.lowercase()
        val abis = ABI_MARKERS.filter { name.contains(it) }
        // supportedAbis 按设备优先级排列，arm64 设备会先匹配到 arm64-v8a。
        val matchingRank = abis
            .mapNotNull { abi -> supported.indexOf(abi).takeIf { it >= 0 } }
            .minOrNull()
        val score = when {
            "universal" in name -> 200
            abis.isEmpty() -> 100
            matchingRank != null -> 50 - matchingRank
            else -> return@mapNotNull null
        }
        asset to score
    }
    return candidates
        .maxWithOrNull(compareBy<Pair<GitHubAsset, Int>> { it.second }.thenBy { it.first.size })
        ?.first
}

/** 把 GitHub 的 Release 变成 App 内的更新描述；版本不比当前新时返回 [UpdateCheckResult.UpToDate]。 */
fun GitHubRelease.toCheckResult(
    current: AppVersion,
    supportedAbis: List<String>,
): UpdateCheckResult {
    if (draft || prerelease) return UpdateCheckResult.NoRelease
    val version = AppVersion.parse(tagName) ?: AppVersion.parse(name) ?: return UpdateCheckResult.NoRelease
    if (version <= current) return UpdateCheckResult.UpToDate
    val apk = selectApkAsset(assets, supportedAbis)
        ?: throw UpdateException("v$version 没有提供兼容当前设备的 APK")
    val sha256 = apk.digest
        ?.trim()
        ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
    return UpdateCheckResult.Available(
        AppUpdate(
            version = version.toString(),
            tagName = tagName,
            title = name.ifBlank { tagName },
            notes = body.orEmpty().trim(),
            pageUrl = htmlUrl,
            publishedAt = publishedAt,
            asset = ReleaseAsset(
                name = apk.name,
                size = apk.size,
                url = apk.downloadUrl,
                sha256 = sha256,
            ),
        ),
    )
}
