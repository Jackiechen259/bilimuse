package com.bilimuse.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WbiTest {
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun mixinKeyMatchesBilibiliTable() {
        assertEquals(
            "ea1db124af3c7062474693fa704f4ff8",
            Wbi.mixinKey(imgKey + subKey),
        )
    }

    @Test
    fun signMatchesPythonImplementation() {
        val signed = Wbi.sign(
            mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"),
            imgKey,
            subKey,
            timestamp = 1_700_000_000,
        )
        assertEquals("1700000000", signed["wts"])
        assertEquals("8ae2d96aa0a4f8c5afa194a6ecca1bf9", signed["w_rid"])
    }

    @Test
    fun filtersSpecialCharactersAndEncodesChinese() {
        assertEquals("夜 航星", Wbi.filter("夜 航星!()*"))
        assertEquals("%E5%A4%9C+%E8%88%AA%E6%98%9F", Wbi.encode(Wbi.filter("夜 航星!()*")))
        val signed = Wbi.sign(
            mapOf("keyword" to "夜 航星!()*"),
            imgKey,
            subKey,
            timestamp = 1_700_000_001,
        )
        assertEquals("93e1c1dfe890916989d425a9688ddf63", signed["w_rid"])
    }

    @Test
    fun encodingKeepsUnreservedCharacters() {
        assertEquals("abc-_.~", Wbi.encode("abc-_.~"))
        assertEquals("a%2Fb%26c", Wbi.encode("a/b&c"))
    }
}
