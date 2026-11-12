package com.bilimuse.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSpecTest {
    @Test
    fun `关闭动效时所有延迟归零`() {
        val spec = MotionSpec(enabled = false)
        assertEquals(0, spec.staggerDelay(0))
        assertEquals(0, spec.staggerDelay(4))
        assertEquals(0, spec.staggerDelay(99))
    }

    @Test
    fun `错峰延迟按步长线性增长`() {
        val spec = MotionSpec(enabled = true)
        assertEquals(0, spec.staggerDelay(0))
        assertEquals(MotionTokens.StaggerStep, spec.staggerDelay(1))
        assertEquals(MotionTokens.StaggerStep * 5, spec.staggerDelay(5))
    }

    @Test
    fun `错峰延迟在下标超过上限后封顶`() {
        val spec = MotionSpec(enabled = true)
        val capped = MotionTokens.StaggerStep * MotionTokens.StaggerCap
        assertEquals(capped, spec.staggerDelay(MotionTokens.StaggerCap))
        assertEquals(capped, spec.staggerDelay(MotionTokens.StaggerCap + 50))
    }
}
