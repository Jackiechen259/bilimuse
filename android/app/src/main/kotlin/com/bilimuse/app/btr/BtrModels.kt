package com.bilimuse.app.btr

import kotlin.math.max
import kotlin.math.pow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** 一段可直接按偏移读取的远程音频。 */
interface Stream {
    val totalSize: Long?
    val stats: Stats

    /** [start, endExclusive) 区间；每调用一次都从当前位置新建一条顺序读取器。 */
    fun openReader(start: Long = 0L, endExclusive: Long? = null): ChunkReader

    fun snapshot(): EngineSnapshot

    fun close() {}
}

/** 阻塞式顺序读取器：给 Media3 的 DataSource 用。 */
interface ChunkReader {
    /** 返回读到的字节数，EOF 返回 -1。 */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

class StreamException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** 单个 CDN 节点的实时状态。 */
class Source(
    val url: String,
    label: String = "",
    @Volatile var speed: Double = 0.0,
    @Volatile var ttfb: Double = 0.5,
    @Volatile var okBytes: Long = 0L,
    @Volatile var errors: Int = 0,
) {
    val label: String = if (label.isNotEmpty()) label else url.substringAfter("://").substringBefore('/')

    /** 速度为主，延迟做轻微惩罚；出错的节点降权。 */
    val score: Double
        get() = speed / (1.0 + ttfb) / (1.0 + errors)
}

/** 按实时测速排序的节点池，内存里只保留少量可变状态。 */
class CdnPool(initial: List<Source>) {
    private val lock = Any()
    private val _sources = initial.toMutableList()

    val sources: List<Source>
        get() = synchronized(lock) { _sources.toList() }

    val size: Int
        get() = synchronized(lock) { _sources.size }

    fun bestSpeed(): Double = synchronized(lock) { _sources.maxOfOrNull { it.speed } ?: 0.0 }

    fun pick(exclude: Set<String> = emptySet()): Source? = synchronized(lock) {
        if (_sources.isEmpty()) return null
        var alive = _sources.filter { it.url !in exclude && it.errors < 4 }
        if (alive.isEmpty()) alive = _sources.filter { it.url !in exclude }
        if (alive.isEmpty()) {
            alive = _sources.toList()
            alive.forEach { it.errors = 0 }
        }
        // 慢节点会拖住整条流水线：低于最快节点 60% 的直接排除
        if (alive.size > 1) {
            val best = alive.maxOf { it.speed }
            val fast = alive.filter { it.speed >= best * 0.6 }
            if (fast.isNotEmpty()) alive = fast
        }
        if (alive.size == 1) return alive[0]
        // 按速度平方加权，保留少量探索
        val weights = alive.map { max(it.speed, 1.0).pow(2) }
        val total = weights.sum()
        var roll = Random.nextDouble() * total
        for ((index, weight) in weights.withIndex()) {
            roll -= weight
            if (roll <= 0.0) return alive[index]
        }
        alive.last()
    }

    fun reportOk(src: Source, nbytes: Int, elapsed: Double) {
        if (nbytes <= 0 || elapsed <= 0.0) return
        synchronized(lock) {
            val inst = nbytes / elapsed
            src.speed = if (src.speed <= 0.0) inst else src.speed * 0.7 + inst * 0.3
            src.ttfb = src.ttfb * 0.8 + minOf(elapsed, 5.0) * 0.2
            src.okBytes += nbytes
        }
    }

    fun reportError(src: Source) {
        synchronized(lock) {
            src.errors += 1
            src.speed *= 0.5
        }
    }

    /** 后台新测出的节点入池（同一个 URL 只收一次）。 */
    fun add(src: Source): Boolean = synchronized(lock) {
        if (_sources.any { it.url == src.url }) return false
        _sources.add(src)
        true
    }
}

class Stats {
    private val startedNs = System.nanoTime()
    val chunksDone = AtomicInteger()
    val bytesDone = AtomicLong()
    val bytesServed = AtomicLong()
    val retries = AtomicInteger()
    val hedges = AtomicInteger()

    private var prevBytes = 0L
    private var prevNs = System.nanoTime()
    private val sampleLock = Any()

    val elapsed: Double
        get() = max((System.nanoTime() - startedNs) / 1e9, 1e-6)

    val speed: Double
        get() = bytesDone.get() / elapsed

    /** 两次采样之间的瞬时速度（暂停/下载完成后会自然降到 0）。 */
    fun sampleSpeed(): Double = synchronized(sampleLock) {
        val now = System.nanoTime()
        val deltaT = (now - prevNs) / 1e9
        if (deltaT <= 0.0) return 0.0
        val rate = (bytesDone.get() - prevBytes) / deltaT
        prevBytes = bytesDone.get()
        prevNs = now
        max(0.0, rate)
    }
}

data class NodeSnapshot(
    val host: String,
    val speed: Long,
    val ttfb: Double,
    val errors: Int,
)

data class EngineSnapshot(
    val mode: String,
    val nodes: List<NodeSnapshot>,
    val concurrency: Int,
    val chunkSize: Int,
    val chunksDone: Int,
    val chunksTotal: Int,
    val bytesDone: Long,
    val bytesServed: Long,
    val progress: Double,
    val speed: Long,
    val recentSpeed: Long,
    val hedges: Int,
    val retries: Int,
)

internal data class ProbeResult(
    val total: Long,
    val supportsRange: Boolean,
    val ttfb: Double,
    val throughput: Double,
)

internal class ProbeOutcome(val url: String, val result: ProbeResult?)
