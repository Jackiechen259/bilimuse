package com.bilimuse.app.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.bilimuse.app.BuildConfig
import com.bilimuse.app.data.BiliClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 安卓版自动更新：检查 GitHub Releases、下载 APK、校验安装包。
 *
 * 更新源是 GitHub 的 `/releases/latest`，因此只有**已发布**的 Release 会被看到，
 * 草稿和预发布不会打扰用户。下载完成后会做三层校验：
 *
 * 1. GitHub 资产摘要（`sha256:`，如果有）；
 * 2. APK 包名 / versionCode / versionName；
 * 3. APK 签名是否与当前安装的应用一致（防止签名不同的包覆盖安装失败）。
 */
class GitHubUpdater(
    context: Context,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val api: GitHubReleaseApi = GitHubReleaseApi(BuildConfig.UPDATE_REPO),
    private val httpClient: OkHttpClient = BiliClient.defaultHttpClient(),
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _autoCheckEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK, true))
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    private val currentVersion = AppVersion.parse(currentVersionName) ?: AppVersion(0, 0, 0)

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean {
        if (!_autoCheckEnabled.value) return false
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return now - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        _autoCheckEnabled.value = enabled
    }

    suspend fun check(): UpdateCheckResult {
        val result = api.latest(currentVersion, Build.SUPPORTED_ABIS.toList())
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        return result
    }

    /**
     * 下载 APK 到应用专属目录。返回的文件已经过大小和 sha256 校验，
     * 安装前还应调用 [validateApk] 检查包名、版本和签名。
     */
    suspend fun download(
        update: AppUpdate,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = updateDir()
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw UpdateException("无法创建更新目录")
        }
        dir.listFiles()?.forEach { it.delete() }

        // 文件名带时间戳：取消后立刻重试时，旧协程的收尾不会误删新下载的临时文件。
        val stamp = System.currentTimeMillis().toString(36)
        val target = File(dir, "BiliMuse-${safeName(update.version)}-$stamp.apk")
        val part = File(dir, "${target.name}.part")
        try {
            val request = Request.Builder()
                .url(update.asset.url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException("下载更新失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw UpdateException("下载更新失败：响应为空")
                val total = body.contentLength().takeIf { it > 0L } ?: update.asset.size
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                onProgress(downloaded, total)
                                lastReport = now
                            }
                        }
                        output.fd.sync()
                    }
                }
            }

            val expectedSize = update.asset.size
            if (expectedSize > 0L && part.length() != expectedSize) {
                throw UpdateException("下载不完整：${part.length()} / $expectedSize 字节")
            }
            val expectedSha = update.asset.sha256
            if (!expectedSha.isNullOrBlank()) {
                val actual = sha256(part)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    throw UpdateException("更新包校验失败（sha256 不一致）")
                }
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(target.length(), target.length())
            target
        } catch (e: CancellationException) {
            part.delete()
            target.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            target.delete()
            if (e is UpdateException) throw e
            throw UpdateException("下载更新失败：${e.message ?: "未知错误"}", e)
        }
    }

    /**
     * 校验下载到的 APK 是否真的能覆盖安装。返回 null 表示通过，否则返回展示给用户的错误。
     */
    fun validateApk(file: File, update: AppUpdate): String? {
        if (!file.isFile || file.length() <= 0L) return "更新包不存在或为空"
        val flags = signatureFlags()
        val archive = try {
            app.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } catch (e: Exception) {
            null
        } ?: return "更新包无法解析，可能下载不完整"

        if (archive.packageName != app.packageName) {
            return "更新包不是 BiliMuse（包名 ${archive.packageName}），已取消安装"
        }
        val installed = try {
            app.packageManager.getPackageInfo(app.packageName, flags)
        } catch (e: Exception) {
            return "无法读取当前应用的版本信息"
        }
        val installedCode = versionCode(installed)
        val archiveCode = versionCode(archive)
        if (archiveCode <= installedCode) {
            return "更新包版本（$archiveCode）不高于当前版本（$installedCode）"
        }
        val installedSignatures = fingerprints(installed, includeHistory = true)
        val archiveSignatures = fingerprints(archive, includeHistory = false)
        if (installedSignatures.isNotEmpty() &&
            archiveSignatures.isNotEmpty() &&
            installedSignatures.intersect(archiveSignatures).isEmpty()
        ) {
            return "更新包签名与当前应用不一致，无法覆盖安装。请到 GitHub Releases 手动下载"
        }
        val archiveVersion = AppVersion.parse(archive.versionName)
        if (archiveVersion != null && archiveVersion.toString() != update.version) {
            return "更新包内部版本（$archiveVersion）与 Release（${update.version}）不一致"
        }
        return null
    }

    private fun updateDir(): File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "updates")

    private fun safeName(version: String): String =
        version.replace(Regex("[^0-9A-Za-z._-]"), "_")

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun fingerprints(info: PackageInfo, includeHistory: Boolean): Set<String> {
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (includeHistory) {
                signing.signingCertificateHistory.orEmpty().toList()
            } else {
                signing.apkContentsSigners.orEmpty().toList()
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().toList()
        }
        return signatures.mapTo(LinkedHashSet()) { sha256(it.toByteArray()) }
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private companion object {
        const val PREFS = "bilimuse_update"
        const val KEY_AUTO_CHECK = "auto_check"
        const val KEY_LAST_CHECK = "last_check"
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val PROGRESS_INTERVAL_MS = 120L
        const val USER_AGENT = "BiliMuse-Android"
        const val HEX = "0123456789abcdef"
    }
}
