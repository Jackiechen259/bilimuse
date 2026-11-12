package com.bilimuse.app.player

/**
 * 把 Media3 时间线里的随机顺序展开成队列面板的行顺序（值为时间线下标）。
 *
 * ExoPlayer 打开随机模式后，时间线本身仍是入队顺序；只有带 `shuffleModeEnabled = true`
 * 的 [androidx.media3.common.Timeline.getFirstWindowIndex] /
 * [androidx.media3.common.Timeline.getNextWindowIndex] 会按内部的 ShuffleOrder 走。
 * 从随机顺序的第一首开始遍历一遍，就能得到「这一轮随机」的完整排列，让面板里的
 * 下一行、`03 / 128` 和真正会播放的下一首完全一致。
 *
 * [first] / [next] 由调用方绑定到时间线。时间线实现异常（重复、越界、提前结束）时
 * 按时间线顺序补齐剩余窗口，保证面板不会漏歌，也不会死循环。
 */
internal fun shuffledQueueOrder(
    count: Int,
    first: () -> Int,
    next: (Int) -> Int,
): IntArray {
    if (count <= 0) return IntArray(0)
    val order = IntArray(count)
    val placed = BooleanArray(count)
    var filled = 0
    var index = first()
    while (index in 0 until count && filled < count && !placed[index]) {
        order[filled++] = index
        placed[index] = true
        index = next(index)
    }
    if (filled < count) {
        for (fallback in 0 until count) {
            if (!placed[fallback]) order[filled++] = fallback
        }
    }
    return order
}
