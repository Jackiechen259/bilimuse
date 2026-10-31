package com.bilimuse.app.data.update

import com.bilimuse.app.data.BiliClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubReleaseApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun noReleaseReturnsNoRelease() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"message":"Not Found"}"""),
        )
        val result = api().latest(current(), listOf("arm64-v8a"))
        assertEquals(UpdateCheckResult.NoRelease, result)
    }

    @Test
    fun parsesLatestReleaseAndApkDigest() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(RELEASE_JSON),
        )
        val result = api().latest(current(), listOf("arm64-v8a"))
        assertTrue(result is UpdateCheckResult.Available)
        val update = (result as UpdateCheckResult.Available).update
        assertEquals("0.2.0", update.version)
        assertEquals("BiliMuse v0.2.0", update.title)
        assertEquals("BiliMuse-android-0.2.0.apk", update.asset.name)
        assertEquals(54321L, update.asset.size)
        assertEquals("deadbeef", update.asset.sha256)
        assertEquals("https://example.com/app.apk", update.asset.url)
        assertTrue(update.notes.contains("自动更新"))

        val request = server.takeRequest()
        assertEquals("/repos/owner/repo/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun rateLimitIsReportedClearly() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("X-RateLimit-Remaining", "0")
                .setBody("""{"message":"API rate limit exceeded"}"""),
        )
        val error = assertThrows(UpdateException::class.java) {
            runBlocking { api().latest(current(), listOf("arm64-v8a")) }
        }
        assertTrue(error.message!!.contains("上限"))
    }

    @Test
    fun malformedJsonRaisesUpdateException() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("this is not json"),
        )
        val error = assertThrows(UpdateException::class.java) {
            runBlocking { api().latest(current(), listOf("arm64-v8a")) }
        }
        assertTrue(error.message!!.contains("无法解析"))
    }

    private fun api(): GitHubReleaseApi = GitHubReleaseApi(
        repo = "owner/repo",
        baseUrl = server.url("/").toString(),
        httpClient = BiliClient.defaultHttpClient(),
    )

    private fun current(): AppVersion = AppVersion.parse("0.1.0")!!

    private companion object {
        val RELEASE_JSON = """
            {
              "tag_name": "v0.2.0",
              "name": "BiliMuse v0.2.0",
              "body": "## 更新内容\n- 新增自动更新\n",
              "html_url": "https://github.com/owner/repo/releases/tag/v0.2.0",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-10-31T00:00:00Z",
              "assets": [
                {
                  "name": "BiliMuse-android-0.2.0.apk",
                  "size": 54321,
                  "content_type": "application/vnd.android.package-archive",
                  "digest": "sha256:DEADBEEF",
                  "browser_download_url": "https://example.com/app.apk"
                },
                {
                  "name": "latest.json",
                  "size": 100,
                  "browser_download_url": "https://example.com/latest.json"
                }
              ]
            }
        """.trimIndent()
    }
}
