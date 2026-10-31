package com.bilimuse.app.data.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateModelsTest {

    @Test
    fun parsesVersionTags() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(0, 2, 0), AppVersion.parse("0.2"))
        assertEquals(AppVersion(1, 0, 0, listOf("beta", "2")), AppVersion.parse("v1.0.0-beta.2"))
        assertEquals(AppVersion.parse("1.2.3"), AppVersion.parse("1.2.3+20261031"))
        assertEquals("1.2.3", AppVersion.parse("v1.2.3")!!.toString())
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v1.2.3.4"))
    }

    @Test
    fun ordersVersionsSemantically() {
        assertTrue(AppVersion.parse("1.10.0")!! > AppVersion.parse("1.9.9")!!)
        assertTrue(AppVersion.parse("2.0.0")!! > AppVersion.parse("1.99.99")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("1.0.0-rc.1")!!)
        assertTrue(AppVersion.parse("1.0.0-beta.10")!! > AppVersion.parse("1.0.0-beta.2")!!)
        assertEquals(0, AppVersion.parse("0.1.0")!!.compareTo(AppVersion.parse("v0.1.0")!!))
    }

    @Test
    fun prefersUniversalApkThenMatchingAbi() {
        val universal = listOf(
            apk("BiliMuse-arm64-v8a.apk"),
            apk("BiliMuse-universal.apk"),
            apk("BiliMuse-x86.apk"),
        )
        assertEquals("BiliMuse-universal.apk", selectApkAsset(universal, listOf("arm64-v8a"))!!.name)

        val perAbi = listOf(
            apk("BiliMuse-arm64-v8a.apk"),
            apk("BiliMuse-armeabi-v7a.apk"),
            apk("BiliMuse-x86.apk"),
        )
        assertEquals("BiliMuse-arm64-v8a.apk", selectApkAsset(perAbi, listOf("arm64-v8a"))!!.name)
        assertEquals("BiliMuse-armeabi-v7a.apk", selectApkAsset(perAbi, listOf("armeabi-v7a"))!!.name)
        // 64 位设备同时支持 32 位 ABI 时，优先设备首选 ABI。
        assertEquals(
            "BiliMuse-arm64-v8a.apk",
            selectApkAsset(perAbi, listOf("arm64-v8a", "armeabi-v7a"))!!.name,
        )
    }

    @Test
    fun skipsIncompatibleAbiAndNonApkAssets() {
        val assets = listOf(
            apk("BiliMuse-x86.apk"),
            GitHubAsset(
                name = "latest.json",
                size = 10,
                downloadUrl = "https://example.com/latest.json",
            ),
        )
        assertNull(selectApkAsset(assets, listOf("arm64-v8a")))
        assertEquals(
            "BiliMuse-android-0.2.0.apk",
            selectApkAsset(listOf(apk("BiliMuse-android-0.2.0.apk")), emptyList())!!.name,
        )
    }

    @Test
    fun convertsReleaseIntoAvailableUpdate() {
        val release = GitHubRelease(
            tagName = "v0.2.0",
            name = "BiliMuse v0.2.0",
            body = "  修复若干问题\n新增自动更新  ",
            htmlUrl = "https://github.com/owner/repo/releases/tag/v0.2.0",
            publishedAt = "2026-10-31T00:00:00Z",
            assets = listOf(
                GitHubAsset(
                    name = "BiliMuse-android-0.2.0.apk",
                    size = 12345,
                    contentType = "application/vnd.android.package-archive",
                    downloadUrl = "https://example.com/BiliMuse-android-0.2.0.apk",
                    digest = "sha256:ABC123",
                ),
            ),
        )
        val result = release.toCheckResult(
            AppVersion.parse("0.1.0")!!,
            listOf("arm64-v8a"),
        )
        assertTrue(result is UpdateCheckResult.Available)
        val update = (result as UpdateCheckResult.Available).update
        assertEquals("0.2.0", update.version)
        assertEquals("BiliMuse v0.2.0", update.title)
        assertEquals("修复若干问题\n新增自动更新", update.notes)
        assertEquals("BiliMuse-android-0.2.0.apk", update.asset.name)
        assertEquals(12345L, update.asset.size)
        assertEquals("abc123", update.asset.sha256)
    }

    @Test
    fun sameOrOlderReleaseIsUpToDate() {
        val current = AppVersion.parse("0.2.0")!!
        assertEquals(
            UpdateCheckResult.UpToDate,
            GitHubRelease(tagName = "v0.2.0", assets = listOf(apk("a.apk")))
                .toCheckResult(current, listOf("arm64-v8a")),
        )
        assertEquals(
            UpdateCheckResult.UpToDate,
            GitHubRelease(tagName = "v0.1.9", assets = listOf(apk("a.apk")))
                .toCheckResult(current, listOf("arm64-v8a")),
        )
    }

    @Test
    fun ignoresDraftAndPrerelease() {
        val current = AppVersion.parse("0.1.0")!!
        assertEquals(
            UpdateCheckResult.NoRelease,
            GitHubRelease(
                tagName = "v0.3.0",
                draft = true,
                assets = listOf(apk("a.apk")),
            ).toCheckResult(current, listOf("arm64-v8a")),
        )
        assertEquals(
            UpdateCheckResult.NoRelease,
            GitHubRelease(
                tagName = "v0.3.0",
                prerelease = true,
                assets = listOf(apk("a.apk")),
            ).toCheckResult(current, listOf("arm64-v8a")),
        )
    }

    @Test
    fun newerReleaseWithoutApkIsAnError() {
        val error = assertThrows(UpdateException::class.java) {
            GitHubRelease(
                tagName = "v0.3.0",
                assets = listOf(GitHubAsset(name = "latest.json", downloadUrl = "https://example.com/a")),
            ).toCheckResult(AppVersion.parse("0.1.0")!!, listOf("arm64-v8a"))
        }
        assertTrue(error.message!!.contains("没有提供"))
    }

    @Test
    fun parsesUnknownFieldsInReleaseJson() {
        val release = parseGitHubRelease(
            """
            {
              "tag_name": "v1.2.3",
              "name": "BiliMuse v1.2.3",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/owner/repo/releases/tag/v1.2.3",
              "published_at": "2026-10-31T00:00:00Z",
              "assets": [
                {
                  "name": "BiliMuse-android-1.2.3.apk",
                  "size": 2048,
                  "digest": "sha256:deadbeef",
                  "browser_download_url": "https://example.com/app.apk",
                  "uploader": {"login": "someone"},
                  "unknown_field": true
                }
              ],
              "reactions": {"+1": 1}
            }
            """.trimIndent(),
        )
        assertEquals("v1.2.3", release.tagName)
        assertEquals(1, release.assets.size)
        assertEquals("sha256:deadbeef", release.assets.first().digest)
        assertEquals(2048L, release.assets.first().size)
    }

    private fun apk(name: String, size: Long = 1024L): GitHubAsset = GitHubAsset(
        name = name,
        size = size,
        downloadUrl = "https://example.com/$name",
    )
}
