package com.bilimuse.app.player

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PlayOrderTest {
    /** 把一条时间线的 first / next 导航包装成有序序列，方便构造随机排列。 */
    private fun orderOf(count: Int, sequence: List<Int>): IntArray {
        val position = HashMap<Int, Int>()
        sequence.forEachIndexed { index, value -> position[value] = index }
        return shuffledQueueOrder(
            count = count,
            first = { sequence.firstOrNull() ?: -1 },
            next = { index ->
                val offset = position[index]
                if (offset == null || offset + 1 >= sequence.size) -1 else sequence[offset + 1]
            },
        )
    }

    @Test
    fun `shuffle order follows the timeline sequence`() {
        assertArrayEquals(
            intArrayOf(3, 0, 4, 1, 2),
            orderOf(5, listOf(3, 0, 4, 1, 2)),
        )
    }

    @Test
    fun `empty timeline yields empty order`() {
        assertArrayEquals(
            intArrayOf(),
            shuffledQueueOrder(count = 0, first = { -1 }, next = { -1 }),
        )
    }

    @Test
    fun `missing windows are appended in timeline order`() {
        // 时间线只走到 2、4 就结束（例如带广告或异常实现），剩余窗口按原顺序补齐。
        assertArrayEquals(
            intArrayOf(2, 4, 0, 1, 3),
            orderOf(5, listOf(2, 4)),
        )
    }

    @Test
    fun `duplicate indices do not loop forever`() {
        assertArrayEquals(
            intArrayOf(0, 1, 2),
            shuffledQueueOrder(
                count = 3,
                first = { 0 },
                next = { index -> if (index == 0) 1 else 0 },
            ),
        )
    }

    @Test
    fun `out-of-range start falls back to timeline order`() {
        assertArrayEquals(
            intArrayOf(0, 1, 2),
            shuffledQueueOrder(count = 3, first = { 9 }, next = { -1 }),
        )
    }
}
