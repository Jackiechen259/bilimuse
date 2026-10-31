package com.bilimuse.app.data.update

import com.bilimuse.app.data.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 只负责问 GitHub“最新发布版是什么”，不碰文件、不改本地状态。
 *
 * 用 `/releases/latest`：GitHub 只会返回已发布（非草稿、非预发布）的最新版本，
 * 所以草稿 Release 不会推送给用户。更新内容、APK 下载地址和 sha256 摘要
 * 都在同一个响应里。
 */
class GitHubReleaseApi(
    private val repo: String,
    private val baseUrl: String = API_BASE,
    private val httpClient: OkHttpClient = BiliClient.defaultHttpClient(),
) {

    suspend fun latest(
        current: AppVersion,
        supportedAbis: List<String>,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/repos/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw UpdateException("无法连接 GitHub，请检查网络后重试", e)
        }

        response.use { resp ->
            when {
                resp.code == 404 -> UpdateCheckResult.NoRelease
                resp.code == 403 || resp.code == 429 -> {
                    if (resp.header("X-RateLimit-Remaining") == "0") {
                        throw UpdateException("GitHub API 请求次数已达上限，请稍后再试")
                    }
                    throw UpdateException("GitHub 拒绝了更新请求（HTTP ${resp.code}）")
                }
                !resp.isSuccessful -> throw UpdateException("检查更新失败：HTTP ${resp.code}")
                else -> {
                    val body = resp.body?.string().orEmpty()
                    val release = try {
                        parseGitHubRelease(body)
                    } catch (e: IllegalArgumentException) {
                        throw UpdateException("GitHub 返回了无法解析的更新信息", e)
                    }
                    release.toCheckResult(current, supportedAbis)
                }
            }
        }
    }

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val USER_AGENT = "BiliMuse-Android"
    }
}
