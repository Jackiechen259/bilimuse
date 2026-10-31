package com.bilimuse.app.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class DemoTrack(
    val bvid: String,
    val title: String,
    val upper: String,
    val duration: Int,
    val intro: String,
    val file: String,
    val root: Double,
)

/**
 * 演示模式：本地合成三段音频，通过一个 OkHttp Interceptor 伪装成限速 CDN。
 *
 * 这样不用 B 站账号也能在真机上跑通「探测 → 分块并发 → 对冲 → 播放」整条链路，
 * 而且单连接和多连接的差别是真实可测的（每条连接限速 320 KB/s）。
 */
class DemoCdn(cacheDir: File) {
    private val audioDir = File(cacheDir, "demo")
    private val lock = Any()

    fun ensureAll() {
        DemoData.tracks.forEach { ensureAudio(it.file) }
    }

    fun ensureAudio(name: String): File = synchronized(lock) {
        audioDir.mkdirs()
        val file = File(audioDir, name)
        if (file.exists() && file.length() > 1024) return file
        val track = DemoData.tracks.firstOrNull { it.file == name } ?: return file
        val tmp = File(audioDir, "$name.tmp")
        FileOutputStream(tmp).use { writeWav(it, track.root, track.duration) }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
        file
    }

    private fun writeWav(output: FileOutputStream, root: Double, seconds: Int) {
        val sampleRate = 44_100
        val loopSeconds = 4
        val loopFrames = sampleRate * loopSeconds
        val loop = ByteArray(loopFrames * 2)
        val chords = arrayOf(
            doubleArrayOf(1.0, 1.25, 1.5),
            doubleArrayOf(1.0, 1.2, 1.5),
            doubleArrayOf(1.0, 1.33, 1.66),
            doubleArrayOf(1.0, 1.25, 1.6),
        )
        for (i in 0 until loopFrames) {
            val t = i.toDouble() / sampleRate
            val bar = (t / 2).toInt() % chords.size
            val note = (t * 4).toInt() % 3
            val frequency = root * chords[bar][note]
            val phase = t % 0.25
            val env = min(1.0, phase * 8) * exp(-phase * 3)
            val pad = sin(2 * Math.PI * root * 0.5 * t) * 0.12
            val sample = (sin(2 * Math.PI * frequency * t) * 0.35 +
                sin(2 * Math.PI * frequency * 2 * t) * 0.08) * env + pad
            val value = level(sample)
            loop[i * 2] = (value and 0xFF).toByte()
            loop[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }

        val totalBytes = sampleRate * seconds * 2
        output.write(wavHeader(totalBytes, sampleRate.toLong()))
        var written = 0
        while (written < totalBytes) {
            val count = min(loop.size, totalBytes - written)
            output.write(loop, 0, count)
            written += count
        }
    }

    private fun level(sample: Double): Int {
        val clamped = max(-1.0, min(1.0, sample))
        return (clamped * 26000).toInt() and 0xFFFF
    }

    private fun wavHeader(dataSize: Int, sampleRate: Long): ByteArray {
        val header = ByteArray(44)
        fun putAscii(offset: Int, text: String) {
            text.forEachIndexed { index, ch -> header[offset + index] = ch.code.toByte() }
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + dataSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, sampleRate.toInt())
        putInt(28, sampleRate.toInt() * 2)
        putShort(32, 2)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, dataSize)
        return header
    }

    fun urlFor(file: String): String = "https://$HOST/audio/$file"

    companion object {
        const val HOST = "demo.bilimuse.local"
        const val RATE = 320_000.0
    }
}

/** 把 demo.bilimuse.local 的请求变成"每连接 320 KB/s"的本地文件流。 */
class DemoCdnInterceptor(private val cdn: DemoCdn) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.equals(DemoCdn.HOST, ignoreCase = true)) {
            return chain.proceed(request)
        }
        val name = request.url.pathSegments.lastOrNull().orEmpty()
        if (name.isEmpty() || name.contains("..") || request.method != "GET") {
            return simpleResponse(request, 404, ByteArray(0))
        }
        val file = cdn.ensureAudio(name)
        if (!file.exists()) {
            return simpleResponse(request, 404, ByteArray(0))
        }
        val total = file.length()
        val range = parseRange(request.header("Range"), total)
        val start = range?.first ?: 0L
        val end = range?.second ?: total
        if (end <= start) {
            return simpleResponse(request, 416, ByteArray(0), total = total)
        }
        val body = ThrottledFileBody(file, start, end, DemoCdn.RATE)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (range != null) 206 else 200)
            .message(if (range != null) "Partial Content" else "OK")
            .header("Content-Type", "audio/wav")
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", (end - start).toString())
            .apply {
                if (range != null) {
                    header("Content-Range", "bytes $start-${end - 1}/$total")
                }
            }
            .body(body)
            .build()
    }

    private fun simpleResponse(
        request: okhttp3.Request,
        code: Int,
        body: ByteArray,
        total: Long? = null,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 404) "Not Found" else "Error")
        .apply { total?.let { header("Content-Range", "bytes */$it") } }
        .body(body.toResponseBody("audio/wav".toMediaTypeOrNull()))
        .build()

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank() || !header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val rawStart = spec.substring(0, dash).trim()
        val rawEnd = spec.substring(dash + 1).trim()
        return if (rawStart.isEmpty()) {
            val length = rawEnd.toLongOrNull() ?: return null
            if (length <= 0) return null
            max(0L, total - length) to total
        } else {
            val start = rawStart.toLongOrNull() ?: return null
            if (start >= total) return null
            val endInclusive = if (rawEnd.isEmpty()) total - 1 else (rawEnd.toLongOrNull() ?: return null)
            val endExclusive = (endInclusive + 1).coerceAtMost(total)
            if (endExclusive <= start) return null
            start to endExclusive
        }
    }
}

private fun ByteArray.toResponseBody(contentType: okhttp3.MediaType?): ResponseBody =
    object : ResponseBody() {
        override fun contentType() = contentType
        override fun contentLength() = this@toResponseBody.size.toLong()
        override fun source(): BufferedSource = Buffer().apply { write(this@toResponseBody) }
    }

/** 一条"被限速到 320 KB/s"的连接：读的时候按字节数 sleep。 */
private class ThrottledFileBody(
    private val file: File,
    private val start: Long,
    private val end: Long,
    private val rate: Double,
) : ResponseBody() {
    override fun contentType() = "audio/wav".toMediaTypeOrNull()
    override fun contentLength() = end - start

    override fun source(): BufferedSource {
        val input = FileInputStream(file)
        input.channel.position(start)
        val remainingBytes = end - start
        val source = object : Source {
            private val buffer = ByteArray(16 * 1024)
            private var remaining = remainingBytes
            private var sent = 0L
            private val startedNs = System.nanoTime()

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining <= 0L) return -1L
                val want = minOf(byteCount, buffer.size.toLong(), remaining).toInt()
                val n = input.read(buffer, 0, want)
                if (n == -1) {
                    remaining = 0
                    return -1L
                }
                sink.write(buffer, 0, n)
                remaining -= n
                sent += n
                val targetNs = (sent * 1e9 / rate).toLong()
                val elapsedNs = System.nanoTime() - startedNs
                val sleepNs = targetNs - elapsedNs
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.InterruptedIOException("demo stream interrupted")
                    }
                }
                return n.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                input.close()
            }
        }
        return source.buffer()
    }
}

object DemoData {
    val folder = Folder(id = -1, title = "演示 · 合成器浪潮", count = 3, favState = 1)

    val tracks = listOf(
        DemoTrack(
            bvid = "DEMO0001",
            title = "夜航星 (Demo)",
            upper = "BiliMuse",
            duration = 45,
            intro = "多线程加载演示：45 秒合成的琶音音轨",
            file = "night_flight.wav",
            root = 220.0,
        ),
        DemoTrack(
            bvid = "DEMO0002",
            title = "城市霓虹 (Demo)",
            upper = "BiliMuse",
            duration = 45,
            intro = "低频铺底 + 和弦，用于测试进度条拖动（Range 请求）",
            file = "neon_city.wav",
            root = 164.81,
        ),
        DemoTrack(
            bvid = "DEMO0003",
            title = "海边的信号 (Demo)",
            upper = "BiliMuse",
            duration = 45,
            intro = "缓慢的环境音，测试连续播放",
            file = "sea_signal.wav",
            root = 196.0,
        ),
    )

    fun trackItems(): List<TrackItem> = tracks.map {
        TrackItem(
            bvid = it.bvid,
            title = it.title,
            cover = "",
            upper = it.upper,
            duration = it.duration,
            intro = it.intro,
        )
    }
}
