package com.bilimuse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieParsingTest {
    @Test
    fun parsesCookieHeaderWithEqualsInValue() {
        val cookies = parseCookieHeader(
            "SESSDATA=a%2Cb; bili_jct=x=y; sid=1; broken; empty=",
        )
        assertEquals("a%2Cb", cookies["SESSDATA"])
        assertEquals("x=y", cookies["bili_jct"])
        assertEquals("1", cookies["sid"])
        assertEquals(3, cookies.size)
    }

    @Test
    fun handlesBlankHeaders() {
        assertTrue(parseCookieHeader(null).isEmpty())
        assertTrue(parseCookieHeader("").isEmpty())
        assertTrue(parseCookieHeader("   ;  ").isEmpty())
    }
}
