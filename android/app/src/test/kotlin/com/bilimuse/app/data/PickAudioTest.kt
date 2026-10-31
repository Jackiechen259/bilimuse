package com.bilimuse.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickAudioTest {
    private val dash = Json.parseToJsonElement(
        """
        {
          "flac": {
            "display": true,
            "audio": {
              "id": 30251,
              "baseUrl": "http://upos.example.com/flac/audio.flac?deadline=1",
              "backupUrl": ["http://backup.example.com/flac/audio.flac?deadline=1"],
              "bandwidth": 900000,
              "codecs": "fLaC",
              "mimeType": "audio/flac"
            }
          },
          "dolby": {
            "audio": {
              "id": 30250,
              "baseUrl": "https://upos.example.com/dolby/audio.m4s",
              "backupUrl": [],
              "bandwidth": 448000,
              "codecs": "ec-3",
              "mimeType": "audio/mp4"
            }
          },
          "audio": [
            {"id": 30216, "baseUrl": "https://upos.example.com/a64.m4s", "bandwidth": 64000, "codecs": "mp4a.40.2", "mimeType": "audio/mp4"},
            {"id": 30280, "baseUrl": "https://upos.example.com/a192.m4s", "bandwidth": 192000, "codecs": "mp4a.40.2", "mimeType": "audio/mp4"}
          ]
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun prefersLosslessAndUpgradesToHttps() {
        val audio = pickAudio(dash, preferLossless = true)
        assertEquals("Hi-Res 无损", audio.quality)
        assertTrue(audio.url.startsWith("https://"))
        assertEquals("https://upos.example.com/flac/audio.flac?deadline=1", audio.url)
        assertEquals(
            listOf("https://backup.example.com/flac/audio.flac?deadline=1"),
            audio.backups,
        )
        assertEquals("audio/flac", audio.mime)
    }

    @Test
    fun picksHighestBandwidthWhenLosslessDisabled() {
        val audio = pickAudio(dash, preferLossless = false)
        assertEquals("192K", audio.quality)
        assertEquals(192000, audio.bandwidth)
    }

    @Test(expected = BiliException::class)
    fun throwsWhenNoAudioStream() {
        pickAudio(Json.parseToJsonElement("""{"dash":{}}""").jsonObject, true)
    }
}
