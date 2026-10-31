package com.bilimuse.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.bilimuse.app.btr.ChunkReader
import com.bilimuse.app.data.StreamRegistry
import java.io.IOException

/**
 * Media3 的 DataSource：把 ExoPlayer 的顺序读取接到 BTR 引擎上。
 *
 * URI 形如 bilimuse://track/{bvid}。ExoPlayer 每次 seek 会 close/reopen，
 * 我们按 position 新建一条 BTR 顺序读取器，底层节点池和统计继续复用。
 */
class BtrDataSource(
    private val registry: StreamRegistry,
) : BaseDataSource(false) {

    private var reader: ChunkReader? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.position < 0) {
            throw IOException("无效的播放位置")
        }
        val bvid = dataSpec.uri.lastPathSegment
            ?: throw IOException("无效的音轨地址")
        // 队列里的歌是懒解析的：第一次真正开播时才去取 playurl + 探测节点。
        // 已解析过的会命中 30 分钟缓存，几乎立即返回。
        registry.resolveBlocking(bvid)
        val stream = registry.streamFor(bvid)
            ?: throw IOException("音频已过期，请重新点击播放")
        val endExclusive = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            null
        } else {
            dataSpec.position + dataSpec.length
        }
        reader = stream.openReader(dataSpec.position, endExclusive)
        uri = dataSpec.uri
        opened = true
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            stream.totalSize?.minus(dataSpec.position) ?: C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val max = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = reader?.read(buffer, offset, max) ?: C.RESULT_END_OF_INPUT
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val active = reader
        reader = null
        try {
            active?.close()
        } finally {
            if (opened) {
                opened = false
                uri = null
                transferEnded()
            }
        }
    }

    class Factory(private val registry: StreamRegistry) : DataSource.Factory {
        override fun createDataSource(): DataSource = BtrDataSource(registry)
    }
}
