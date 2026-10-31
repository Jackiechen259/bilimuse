package com.bilimuse.app.data

import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DemoCdnTest {
    @Test
    fun generatesWavAndServesRange() {
        val dir = Files.createTempDirectory("bilimuse-demo").toFile()
        val client = StreamRegistry.createMediaClient(DemoCdnInterceptor(DemoCdn(dir)))
        try {
            val cdn = DemoCdn(dir)
            val file = cdn.ensureAudio("night_flight.wav")
            assertEquals(true, file.exists())
            assertTrue("演示音频应接近 4MB，实际 ${file.length()}", file.length() > 1_000_000)

            val header = file.inputStream().use { it.readNBytes(12) }
            assertEquals("RIFF", String(header, 0, 4))
            assertEquals("WAVE", String(header, 8, 4))

            val request = Request.Builder()
                .url(cdn.urlFor("night_flight.wav"))
                .header("Range", "bytes=100-1099")
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(206, response.code)
                assertEquals(
                    "bytes 100-1099/${file.length()}",
                    response.header("Content-Range"),
                )
                val body = response.body!!.bytes()
                assertEquals(1000, body.size)
                val bytes = file.readBytes()
                assertArrayEquals(bytes.copyOfRange(100, 1100), body)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            dir.deleteRecursively()
        }
    }
}
