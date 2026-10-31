package com.bilimuse.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDataTest {
    @Test
    fun roundTripsThroughJson() {
        val session = SessionData(
            cookies = mapOf("SESSDATA" to "abc%2C123", "bili_jct" to "token"),
            mid = 42L,
            uname = "测试用户",
            face = "https://example.com/face.jpg",
            vip = true,
            updatedAt = 1_700_000_000L,
        )
        val encoded = Json.encodeToString(SessionData.serializer(), session)
        val decoded = Json.decodeFromString(SessionData.serializer(), encoded)
        assertEquals(session, decoded)
        assertTrue(decoded.loggedIn)
        assertEquals("SESSDATA=abc%2C123; bili_jct=token", decoded.cookieHeader())
    }

    @Test
    fun sessionWithoutCookiesIsNotLoggedIn() {
        assertFalse(SessionData(mid = 42).loggedIn)
        assertFalse(SessionData(cookies = mapOf("SESSDATA" to "x")).loggedIn)
    }
}
