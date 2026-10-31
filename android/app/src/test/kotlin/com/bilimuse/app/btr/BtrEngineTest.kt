package com.bilimuse.app.btr

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class BtrEngineTest {

    @Test
    fun btrIsFasterAndByteExact() = runBlocking {
        val payload = ByteArray(1024 * 1024)
        Random(7).nextBytes(payload)
        val server = startCdn(payload)
        val client = newClient()
        try {
            val url = server.url("/audio/test.m4s").toString()
            val single = buildStream(client, url, btr = false, useMirrors = false)
            val (singleBytes, singleMs) = timeAndRead(single)

            val btrStream = buildStream(
                client = client,
                url = url,
                concurrency = 8,
                chunkSize = 256 * 1024,
                btr = true,
                useMirrors = false,
            )
            val (btrBytes, btrMs) = timeAndRead(btrStream)

            println(
                "BTR benchmark: single=${singleMs}ms, btr=${btrMs}ms, " +
                    "speedup=${"%.1f".format(singleMs.toDouble() / btrMs)}x",
            )
            assertEquals(payload.size.toLong(), btrStream.totalSize ?: -1L)
            assertArrayEquals(payload, singleBytes)
            assertArrayEquals(payload, btrBytes)
            assertTrue(
                "BTR 应显著快于单连接: btr=${btrMs}ms single=${singleMs}ms",
                btrMs * 2 < singleMs,
            )
        } finally {
            server.shutdown()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun rangeSeekIsByteExact() = runBlocking {
        val payload = ByteArray(512 * 1024)
        Random(11).nextBytes(payload)
        val server = startCdn(payload, bytesPerSecond = 800_000)
        val client = newClient()
        try {
            val stream = buildStream(
                client = client,
                url = server.url("/audio/seek.m4s").toString(),
                concurrency = 4,
                chunkSize = 128 * 1024,
                btr = true,
                useMirrors = false,
            )
            val start = 123_456L
            val end = 300_001L
            val reader = stream.openReader(start, end)
            val collected = ByteArrayOutputStream()
            try {
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = reader.read(buffer, 0, buffer.size)
                    if (read == -1) break
                    collected.write(buffer, 0, read)
                }
            } finally {
                reader.close()
                stream.close()
            }
            assertArrayEquals(
                payload.copyOfRange(start.toInt(), end.toInt()),
                collected.toByteArray(),
            )
        } finally {
            server.shutdown()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun deadNodeDoesNotDelayStartup() = runBlocking {
        val payload = ByteArray(256 * 1024)
        Random(13).nextBytes(payload)
        val good = startCdn(payload)
        val blackHole = MockWebServer()
        blackHole.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(1_500)
                return MockResponse().setResponseCode(500)
            }
        }
        blackHole.start()
        val client = newClient()
        try {
            val started = System.nanoTime()
            val stream = buildStream(
                client = client,
                url = good.url("/audio/eager.m4s").toString(),
                backups = listOf(blackHole.url("/audio/eager.m4s").toString()),
                concurrency = 4,
                chunkSize = 128 * 1024,
                btr = true,
                useMirrors = false,
                eager = true,
            )
            val startupMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                "抢跑不应等黑洞节点，实际 ${startupMs}ms",
                startupMs < 1_200,
            )
            val reader = stream.openReader()
            val collected = ByteArrayOutputStream()
            try {
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = reader.read(buffer, 0, buffer.size)
                    if (read == -1) break
                    collected.write(buffer, 0, read)
                }
            } finally {
                reader.close()
                stream.close()
            }
            assertArrayEquals(payload, collected.toByteArray())
        } finally {
            good.shutdown()
            blackHole.shutdown()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun timeAndRead(stream: Stream): Pair<ByteArray, Long> {
        val started = System.nanoTime()
        val bytes = readAll(stream)
        return bytes to (System.nanoTime() - started) / 1_000_000
    }

    private fun readAll(stream: Stream): ByteArray {
        val reader = stream.openReader()
        val collected = ByteArrayOutputStream()
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = reader.read(buffer, 0, buffer.size)
                if (read == -1) break
                collected.write(buffer, 0, read)
            }
        } finally {
            reader.close()
            stream.close()
        }
        return collected.toByteArray()
    }

    private fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
        .also {
            it.dispatcher.maxRequestsPerHost = 64
            it.dispatcher.maxRequests = 128
        }

    /** 每条连接 400 KB/s 的假 CDN。 */
    private fun startCdn(
        payload: ByteArray,
        bytesPerSecond: Int = 400_000,
    ): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                val (start, end) = parseRange(rangeHeader, payload.size.toLong())
                val length = (end - start).toInt()
                val response = MockResponse()
                    .setResponseCode(if (rangeHeader != null) 206 else 200)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Type", "audio/mp4")
                if (rangeHeader != null) {
                    response.setHeader("Content-Range", "bytes $start-${end - 1}/${payload.size}")
                }
                val buffer = Buffer()
                buffer.write(payload, start.toInt(), length)
                response.setBody(buffer)
                // 16 KB / 40 ms ≈ 400 KB/s，与 README 的基准场景同一量级
                val periodMs = (16L * 1024 * 1000 / bytesPerSecond).coerceAtLeast(1L)
                response.throttleBody(16 * 1024, periodMs, TimeUnit.MILLISECONDS)
                return response
            }
        }
        server.start()
        return server
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
        if (header == null) return 0L to total
        val spec = header.substringAfter("bytes=").substringBefore(',').trim()
        val start = spec.substringBefore('-').toLong()
        val endInclusive = spec.substringAfter('-').ifBlank { (total - 1).toString() }.toLong()
        return start to minOf(total, endInclusive + 1)
    }
}
