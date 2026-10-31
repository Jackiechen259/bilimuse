package com.bilimuse.app.btr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

object BtrConfig {
    val MIRROR_HOSTS = listOf(
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorakam.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-hz-mirrorakam.akamaized.net",
    )

    const val PROBE_BYTES = 64 * 1024
    const val PROBE_TIMEOUT_MS = 3000L
    const val SLOW_NODE_FLOOR = 150.0 * 1024
    const val EAGER_GRACE_MS = 750L
}

/** 从一条 playurl 拼出候选节点列表（备份地址 + 已知镜像 host 互换）。 */
fun expandMirrors(url: String, backups: List<String> = emptyList()): List<String> {
    val base = url.toHttpUrlOrNull() ?: return (listOf(url) + backups).distinct()
    val hosts = LinkedHashSet<String>()
    for (candidate in backups + url) {
        candidate.toHttpUrlOrNull()?.host?.let(hosts::add)
    }
    hosts.addAll(BtrConfig.MIRROR_HOSTS)
    return hosts.map { host -> base.newBuilder().host(host).build().toString() }
}

private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
    headers.forEach { (key, value) ->
        if (!key.equals("Host", ignoreCase = true) && !key.equals("Range", ignoreCase = true)) {
            header(key, value)
        }
    }
    return this
}

internal suspend fun probe(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long = BtrConfig.PROBE_TIMEOUT_MS,
): ProbeResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    val request = Request.Builder()
        .url(url)
        .applyHeaders(headers)
        .header("Range", "bytes=0-${BtrConfig.PROBE_BYTES - 1}")
        .get()
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
    try {
        call.execute().use { response ->
            if (response.code !in setOf(200, 206)) {
                throw StreamException("HTTP ${response.code}")
            }
            val ttfb = (System.nanoTime() - started) / 1e9
            val contentRange = response.header("Content-Range").orEmpty()
            var total = 0L
            if ("/" in contentRange) {
                total = contentRange.substringAfterLast('/').toLongOrNull() ?: 0L
            }
            if (total <= 0L) {
                total = response.body?.contentLength()?.takeIf { it > 0 } ?: 0L
            }
            var received = 0
            val buffer = ByteArray(8 * 1024)
            val input = response.body?.byteStream()
            if (input != null) {
                while (received < BtrConfig.PROBE_BYTES) {
                    val want = min(buffer.size, BtrConfig.PROBE_BYTES - received)
                    val n = input.read(buffer, 0, want)
                    if (n == -1) break
                    received += n
                }
            }
            val elapsed = max((System.nanoTime() - started) / 1e9, 1e-3)
            ProbeResult(
                total = total,
                supportsRange = response.code == 206,
                ttfb = ttfb,
                throughput = received / elapsed,
            )
        }
    } catch (e: IOException) {
        throw if (e is StreamException) e else StreamException(e.message ?: "节点不可用", e)
    }
}

/**
 * BTR 多线程流式引擎。
 *
 * 把整段音频切成块，多块并发下载（可跨 CDN 节点），乱序到达后按偏移量顺序
 * 交给播放器；对迟迟不回的块做对冲，断流则从断点续传。
 */
class BtrStream internal constructor(
    private val client: OkHttpClient,
    sources: List<Source>,
    override val totalSize: Long,
    private val headers: Map<String, String>,
    initialConcurrency: Int,
    maxConcurrency: Int,
    val chunkSize: Int,
    private val prefetchChunks: Int = 2,
    @Volatile var hedgeDelay: Double = 1.2,
    private val maxRounds: Int = 6,
    private val scope: CoroutineScope,
) : Stream {

    init {
        require(totalSize > 0) { "总长度未知，不能用 BTR" }
    }

    val pool = CdnPool(sources)

    @Volatile
    var concurrency: Int = initialConcurrency.coerceAtLeast(1)
        private set

    @Volatile
    var maxConcurrency: Int = maxConcurrency.coerceAtLeast(1)

    override val stats = Stats()

    /** 设置页改并发数后，立即作用到已经解析好的流上。 */
    fun applyLimits(maxConcurrency: Int) {
        this.maxConcurrency = maxConcurrency.coerceAtLeast(1)
        if (concurrency > this.maxConcurrency) {
            concurrency = this.maxConcurrency
        }
    }

    /** 后台测速完成后把新节点塞进池子；太慢的直接丢掉。 */
    fun addSource(src: Source): Boolean {
        val floor = max(BtrConfig.SLOW_NODE_FLOOR, pool.bestSpeed() * 0.3)
        if (src.speed < floor) return false
        if (!pool.add(src)) return false
        val best = max(pool.bestSpeed(), src.speed)
        val expected = chunkSize / max(best, 1.0)
        hedgeDelay = min(2.5, max(0.8, expected * 2.0))
        return true
    }

    private fun rampUp() {
        if (concurrency < maxConcurrency) {
            concurrency = min(maxConcurrency, concurrency + 2)
        }
    }

    // ------------------------------------------------------------ 单请求

    /** 请求 [offset, offset+length)；断流时返回已收到的部分。 */
    private suspend fun download(src: Source, offset: Long, length: Int): ByteArray {
        assert(length > 0)
        val request = Request.Builder()
            .url(src.url)
            .applyHeaders(headers)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .get()
            .build()
        val call = client.newCall(request)
        val job = currentCoroutineContext()[Job]
        val onCancel = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        val started = System.nanoTime()
        val buffer = ByteArrayOutputStream(length)
        try {
            call.execute().use { response ->
                if (response.code !in setOf(200, 206)) {
                    throw StreamException("HTTP ${response.code} @ ${src.label}")
                }
                var skip = if (response.code == 200) offset else 0L
                val input: InputStream = response.body?.byteStream()
                    ?: throw StreamException("空响应 @ ${src.label}")
                val block = ByteArray(64 * 1024)
                while (buffer.size() < length) {
                    currentCoroutineContext().ensureActive()
                    val room = length - buffer.size()
                    val want = min(block.size, room)
                    val n = input.read(block, 0, want)
                    if (n == -1) break
                    var dataOffset = 0
                    var dataLength = n
                    if (skip > 0) {
                        if (n <= skip) {
                            skip -= n
                            continue
                        }
                        dataOffset = skip.toInt()
                        dataLength = n - dataOffset
                        skip = 0
                    }
                    buffer.write(block, dataOffset, min(dataLength, length - buffer.size()))
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            if (buffer.size() == 0) {
                pool.reportError(src)
                throw if (e is StreamException) e else StreamException(e.message ?: "网络错误", e)
            }
            stats.retries.incrementAndGet()
        } finally {
            onCancel?.dispose()
        }
        val bytes = buffer.toByteArray()
        if (bytes.size == length) {
            pool.reportOk(src, bytes.size, (System.nanoTime() - started) / 1e9)
        } else {
            // 少了字节说明连接被截断，不能算这个节点"快"
            pool.reportError(src)
        }
        stats.bytesDone.addAndGet(bytes.size.toLong())
        return bytes
    }

    /** 先在一个节点上请求；超时未回则另开一个节点重发同一段，谁先到用谁。 */
    private suspend fun hedged(offset: Long, length: Int): ByteArray = supervisorScope {
        val tried = mutableSetOf<String>()
        val tasks = LinkedHashMap<Deferred<Result<ByteArray>>, Source>()

        fun launchOne(): Boolean {
            val src = pool.pick(tried) ?: return false
            tried.add(src.url)
            val task = async(Dispatchers.IO) {
                try {
                    Result.success(download(src, offset, length))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            tasks[task] = src
            return true
        }

        if (!launchOne()) {
            throw StreamException("区间 $offset+$length 没有可用节点")
        }
        var deadline = System.nanoTime() + (hedgeDelay * 1e9).toLong()
        try {
            while (tasks.isNotEmpty()) {
                val waitMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1L)
                val done = withTimeoutOrNull(waitMs) {
                    select<Pair<Deferred<Result<ByteArray>>, Result<ByteArray>>> {
                        tasks.keys.forEach { task ->
                            task.onAwait { value -> task to value }
                        }
                    }
                }
                if (done != null) {
                    val (task, result) = done
                    tasks.remove(task)
                    val data = result.getOrNull()
                    if (data != null && data.isNotEmpty()) {
                        return@supervisorScope data
                    }
                    deadline = System.nanoTime() + (hedgeDelay * 1e9).toLong()
                    continue
                }
                // 还没回包 → 对冲
                if (tasks.size < 2 && launchOne()) {
                    stats.hedges.incrementAndGet()
                    rampUp()
                    deadline = System.nanoTime() + (hedgeDelay * 1e9).toLong()
                } else {
                    deadline = System.nanoTime() + 1_000_000_000L
                }
            }
        } finally {
            tasks.keys.forEach { it.cancel() }
        }
        throw StreamException("区间 $offset+$length 所有节点都失败")
    }

    private suspend fun chunk(offset: Long, length: Int): ByteArray {
        val data = ByteArrayOutputStream(length)
        var rounds = 0
        while (data.size() < length) {
            rounds += 1
            if (rounds > maxRounds) {
                throw StreamException("块 $offset 重试 $maxRounds 次仍失败")
            }
            try {
                val part = hedged(offset + data.size(), length - data.size())
                if (part.isEmpty()) {
                    stats.retries.incrementAndGet()
                    continue
                }
                data.write(part)
            } catch (e: StreamException) {
                stats.retries.incrementAndGet()
                rampUp()
                delay(min(200L * rounds, 1000L))
            }
        }
        stats.chunksDone.incrementAndGet()
        return data.toByteArray()
    }

    // ------------------------------------------------------------ 顺序输出

    override fun openReader(start: Long, endExclusive: Long?): ChunkReader =
        BtrReader(start, endExclusive)

    private sealed interface ChunkEvent {
        class Data(val bytes: ByteArray) : ChunkEvent
        data object End : ChunkEvent
        class Failure(val error: Throwable) : ChunkEvent
    }

    private inner class BtrReader(
        private val start: Long,
        private val endExclusive: Long?,
    ) : ChunkReader {
        private val queue = LinkedBlockingQueue<ChunkEvent>(8)
        private var current: ByteArray? = null
        private var currentPos = 0
        private var failure: Throwable? = null

        private val job = scope.launch { produce() }

        private suspend fun emit(event: ChunkEvent) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (queue.offer(event, 200, TimeUnit.MILLISECONDS)) return
            }
        }

        private suspend fun produce() {
            try {
                val end = min(endExclusive ?: totalSize, totalSize).coerceAtLeast(start)
                if (start >= end) {
                    queue.offer(ChunkEvent.End)
                    return
                }
                val offsets = ArrayList<Long>()
                var offset = start
                while (offset < end) {
                    offsets.add(offset)
                    offset += chunkSize
                }
                coroutineScope {
                    val inflight = LinkedHashMap<Long, Deferred<ByteArray>>()
                    var index = 0
                    try {
                        while (index < offsets.size) {
                            val window = concurrency + prefetchChunks
                            while (inflight.size < window && index + inflight.size < offsets.size) {
                                val off = offsets[index + inflight.size]
                                inflight[off] = async(Dispatchers.IO) {
                                    chunk(off, min(chunkSize.toLong(), end - off).toInt())
                                }
                            }
                            val data = inflight.remove(offsets[index])!!.await()
                            stats.bytesServed.addAndGet(data.size.toLong())
                            emit(ChunkEvent.Data(data))
                            index += 1
                        }
                        queue.offer(ChunkEvent.End)
                    } finally {
                        inflight.values.forEach { it.cancel() }
                    }
                }
            } catch (e: CancellationException) {
                queue.offer(ChunkEvent.End)
                throw e
            } catch (t: Throwable) {
                failure = t
                queue.offer(ChunkEvent.Failure(t))
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                val cur = current
                if (cur == null || currentPos >= cur.size) {
                    val event = try {
                        queue.poll(500, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                    if (event == null) {
                        if (job.isCompleted && queue.isEmpty()) {
                            failure?.let { throw StreamException("音频流读取失败", it) }
                            return -1
                        }
                        continue
                    }
                    when (event) {
                        is ChunkEvent.Data -> {
                            current = event.bytes
                            currentPos = 0
                        }
                        ChunkEvent.End -> {
                            failure?.let { throw StreamException("音频流读取失败", it) }
                            return -1
                        }
                        is ChunkEvent.Failure -> {
                            throw StreamException(
                                event.error.message ?: "音频流读取失败",
                                event.error,
                            )
                        }
                    }
                    continue
                }
                val n = min(length, cur.size - currentPos)
                System.arraycopy(cur, currentPos, buffer, offset, n)
                currentPos += n
                return n
            }
        }

        override fun close() {
            job.cancel()
            queue.clear()
            queue.offer(ChunkEvent.End)
        }
    }

    override fun snapshot(): EngineSnapshot {
        val total = totalSize
        val nodes = pool.sources
            .sortedByDescending { it.score }
            .map {
                NodeSnapshot(
                    host = it.label,
                    speed = it.speed.roundToLong(),
                    ttfb = (it.ttfb * 1000).roundToLong() / 1000.0,
                    errors = it.errors,
                )
            }
        return EngineSnapshot(
            mode = "btr",
            nodes = nodes,
            concurrency = concurrency,
            chunkSize = chunkSize,
            chunksDone = stats.chunksDone.get(),
            chunksTotal = ((total + chunkSize - 1) / chunkSize).toInt(),
            bytesDone = stats.bytesDone.get(),
            bytesServed = stats.bytesServed.get(),
            progress = (stats.bytesDone.get().toDouble() / total).coerceIn(0.0, 1.0),
            speed = stats.speed.roundToLong(),
            recentSpeed = stats.sampleSpeed().roundToLong(),
            hedges = stats.hedges.get(),
            retries = stats.retries.get(),
        )
    }

    override fun close() {
        scope.cancel()
    }
}

/** 单连接直通，BTR 关闭时的降级路径，也用于长度未知的资源。 */
class SimpleStream(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    override val totalSize: Long? = null,
) : Stream {
    override val stats = Stats()

    override fun openReader(start: Long, endExclusive: Long?): ChunkReader =
        SimpleReader(start, endExclusive)

    private inner class SimpleReader(
        private val start: Long,
        private val endExclusive: Long?,
    ) : ChunkReader {
        private var call: okhttp3.Call? = null
        private var response: okhttp3.Response? = null
        private var input: InputStream? = null
        private var skip = 0L
        private var remaining: Long? = endExclusive?.let { max(0L, it - start) }
        private var opened = false
        private var failure: Throwable? = null

        private fun open() {
            if (opened) return
            opened = true
            val builder = Request.Builder().url(url).applyHeaders(headers)
            if (start > 0L || endExclusive != null) {
                val suffix = endExclusive?.let { (it - 1).toString() } ?: ""
                builder.header("Range", "bytes=$start-$suffix")
            }
            val request = builder.get().build()
            val newCall = client.newCall(request)
            call = newCall
            try {
                val resp = newCall.execute()
                response = resp
                if (resp.code !in setOf(200, 206)) {
                    throw StreamException("HTTP ${resp.code}")
                }
                input = resp.body?.byteStream() ?: throw StreamException("空响应")
                skip = if (resp.code == 200) start else 0L
            } catch (e: IOException) {
                failure = e
                throw if (e is StreamException) e else StreamException(e.message ?: "网络错误", e)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            failure?.let { throw StreamException(it.message ?: "网络错误", it) }
            try {
                open()
                val source = input ?: return -1
                val discard = ByteArray(64 * 1024)
                while (true) {
                    if (skip > 0) {
                        val n = source.read(discard, 0, min(discard.size.toLong(), skip).toInt())
                        if (n == -1) return -1
                        skip -= n
                        continue
                    }
                    val limit = remaining
                    if (limit != null && limit <= 0) return -1
                    val want = if (limit == null) length else min(length.toLong(), limit).toInt()
                    if (want <= 0) return -1
                    var n = source.read(buffer, offset, want)
                    if (n == -1) return -1
                    // 理论上这里不会再有要跳过的字节；保留一次防护
                    if (skip > 0) {
                        if (n <= skip) {
                            skip -= n
                            continue
                        }
                        val drop = skip.toInt()
                        System.arraycopy(buffer, offset + drop, buffer, offset, n - drop)
                        n -= drop
                        skip = 0
                    }
                    if (remaining != null) remaining = remaining!! - n
                    stats.bytesDone.addAndGet(n.toLong())
                    stats.bytesServed.addAndGet(n.toLong())
                    return n
                }
            } catch (e: IOException) {
                failure = e
                throw if (e is StreamException) e else StreamException(e.message ?: "网络错误", e)
            }
        }

        override fun close() {
            response?.close()
            call?.cancel()
            response = null
            input = null
        }
    }

    override fun snapshot(): EngineSnapshot {
        val total = totalSize ?: 0L
        return EngineSnapshot(
            mode = "single",
            nodes = listOf(
                NodeSnapshot(
                    host = url.substringAfter("://").substringBefore('/'),
                    speed = 0,
                    ttfb = 0.0,
                    errors = 0,
                ),
            ),
            concurrency = 1,
            chunkSize = 0,
            chunksDone = 0,
            chunksTotal = 0,
            bytesDone = stats.bytesDone.get(),
            bytesServed = stats.bytesServed.get(),
            progress = if (total > 0) {
                (stats.bytesDone.get().toDouble() / total).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
            speed = stats.speed.roundToLong(),
            recentSpeed = stats.sampleSpeed().roundToLong(),
            hedges = 0,
            retries = 0,
        )
    }
}

/**
 * 探测候选节点 → 组装节点池 → 返回可流式读取的对象。
 *
 * eager=true 时不等所有节点测速完毕：第一个真正能用的节点先把流交出去，
 * 剩下的探针在后台继续跑、谁测完谁进池。
 */
suspend fun buildStream(
    client: OkHttpClient,
    url: String,
    backups: List<String> = emptyList(),
    headers: Map<String, String> = emptyMap(),
    concurrency: Int = 8,
    chunkSize: Int = 512 * 1024,
    btr: Boolean = true,
    useMirrors: Boolean = true,
    eager: Boolean = true,
    probeTimeoutMs: Long = BtrConfig.PROBE_TIMEOUT_MS,
): Stream {
    val finalHeaders = HashMap(headers)
    finalHeaders.putIfAbsent("Accept-Encoding", "identity")
    if (!btr) {
        return SimpleStream(client, url, finalHeaders)
    }

    val candidates = if (useMirrors) {
        expandMirrors(url, backups)
    } else {
        (listOf(url) + backups).distinct()
    }

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            // 引擎内部异常不能把整个播放进程带崩；具体错误会通过 reader/异常路径上报
            error.printStackTrace()
        },
    )
    val outcomes = Channel<ProbeOutcome>(Channel.UNLIMITED)
    candidates.forEach { candidate ->
        scope.launch {
            val result = runCatching {
                probe(client, candidate, finalHeaders, probeTimeoutMs)
            }.getOrNull()
            runCatching { outcomes.send(ProbeOutcome(candidate, result)) }
        }
    }

    try {
        val sources = mutableListOf<Source>()
        var total = 0L
        var processed = 0
        val started = System.nanoTime()
        while (processed < candidates.size) {
            val outcome = outcomes.receive()
            processed += 1
            val result = outcome.result ?: continue
            if (!result.supportsRange) continue
            total = if (total > 0) total else result.total
            sources.add(
                Source(
                    url = outcome.url,
                    speed = result.throughput,
                    ttfb = result.ttfb,
                ),
            )
            if (sources.isEmpty() || !eager) continue
            val best = sources.maxOf { it.speed }
            // 首个节点够快就直接开播；太慢（5KB/s 那种坑）就再等一小会儿
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            if (total > 0 && (best >= BtrConfig.SLOW_NODE_FLOOR ||
                        processed >= candidates.size || waitedMs >= BtrConfig.EAGER_GRACE_MS)
            ) {
                break
            }
        }

        if (sources.isEmpty() || total <= 0L) {
            scope.cancel()
            return SimpleStream(client, url, finalHeaders, totalSize = total.takeIf { it > 0 })
        }

        val stream = assemble(
            client = client,
            sources = sources,
            total = total,
            headers = finalHeaders,
            concurrency = concurrency,
            chunkSize = chunkSize,
            scope = scope,
        )
        val remaining = candidates.size - processed
        if (remaining > 0) {
            scope.launch {
                try {
                    repeat(remaining) {
                        val outcome = outcomes.receiveCatching().getOrNull() ?: return@repeat
                        val result = outcome.result ?: return@repeat
                        if (result.supportsRange) {
                            stream.addSource(
                                Source(
                                    url = outcome.url,
                                    speed = result.throughput,
                                    ttfb = result.ttfb,
                                ),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    outcomes.close()
                }
            }
        }
        return stream
    } catch (t: Throwable) {
        scope.cancel()
        throw t
    }
}

private fun assemble(
    client: OkHttpClient,
    sources: List<Source>,
    total: Long,
    headers: Map<String, String>,
    concurrency: Int,
    chunkSize: Int,
    scope: CoroutineScope,
): BtrStream {
    val best = sources.maxOf { it.speed }
    var kept = sources.filter { it.speed >= max(best * 0.3, BtrConfig.SLOW_NODE_FLOOR) }
    if (kept.isEmpty()) {
        kept = sources.filter { it.speed >= best * 0.5 }.ifEmpty { sources.take(1) }
    }
    kept = kept.sortedByDescending { it.score }

    // 块越小、并发越高，起播越快；但请求本身也有开销，所以按文件长度折中
    val chunk = min(chunkSize, max(256 * 1024, (total / 40.0).toInt())).coerceAtLeast(128 * 1024)

    // 自适应并发：目标 4MB/s。节点已经很快时没必要再开 8 条连接
    val target = 4.0 * 1024 * 1024
    val autoConcurrency = max(2, min(concurrency, ceil(target / max(best, 1.0)).toInt()))
    val expected = chunk / max(best, 1.0)
    val hedge = min(2.5, max(0.8, expected * 2.0))
    return BtrStream(
        client = client,
        sources = kept,
        totalSize = total,
        headers = headers,
        initialConcurrency = autoConcurrency,
        maxConcurrency = concurrency,
        chunkSize = chunk,
        hedgeDelay = hedge,
        scope = scope,
    )
}
