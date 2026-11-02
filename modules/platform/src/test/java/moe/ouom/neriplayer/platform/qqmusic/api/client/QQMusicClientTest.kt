package moe.ouom.neriplayer.platform.qqmusic.api.client

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class QQMusicClientTest {
    private val requests = mutableListOf<Request>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val body = when {
            request.bodyText().contains("CgiGetVkey") -> """
                {"code":0,"req_0":{"code":0,"data":{
                  "sip":["https://sip.stream.qqmusic.qq.com/"],
                  "midurlinfo":[{"songmid":"song","purl":"M500songmedia.mp3"}]
                }}}
            """.trimIndent()
            request.bodyText().contains("GetCdnDispatch") -> """
                {"code":0,"req_0":{"code":0,"data":{"sip":[]}}}
            """.trimIndent()
            else -> error("unexpected request: ${request.url}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body.toResponseBody()).build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `download mode returns mirror candidates without consuming probe requests`() = runTest {
        val stream = QQMusicClient(client).getPlaybackStream(
            songMid = "song",
            mediaMid = "media",
            preferredQuality = "standard",
            probeCandidates = false
        )

        assertEquals("https://sip.stream.qqmusic.qq.com/M500songmedia.mp3", stream.url)
        assertEquals(4, stream.candidateUrls.size)
        assertTrue(stream.candidateUrls.any { it.contains("dl.stream.qqmusic.qq.com") })
        assertTrue(stream.candidateUrls.any { it.contains("isure.stream.qqmusic.qq.com") })
        assertTrue(stream.candidateUrls.any { it.contains("ws.stream.qqmusic.qq.com") })
        assertEquals(2, requests.size)
        assertTrue(requests.none { it.url.host.endsWith("stream.qqmusic.qq.com") })
    }

    @Test
    fun `tracks without full permission fall back to official preview clip`() = runTest {
        val previewRequests = mutableListOf<Request>()
        val previewClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            previewRequests += request
            val bodyText = request.bodyText()
            val responseBody = when {
                bodyText.contains("get_song_detail_yqq") -> """
                    {"code":0,"req_0":{"code":0,"data":{"track_info":{
                      "mid":"song","name":"Signal","singer":[{"name":"A"}],
                      "album":{"name":"Album","mid":"album"},"interval":185,
                      "file":{"media_mid":"media"},
                      "vs":["preview-media","",""]
                    }}}}
                """.trimIndent()
                bodyText.contains("GetCdnDispatch") -> """
                    {"code":0,"req_0":{"code":0,"data":{"sip":[]}}}
                """.trimIndent()
                bodyText.contains("RS02") && bodyText.contains("UrlGetVkey") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{
                        "songmid":"song",
                        "filename":"RS02songmedia.mp3",
                        "result":0,
                        "purl":"RS02songmedia.mp3?vkey=preview"
                      }]
                    }}}
                """.trimIndent()
                bodyText.contains("UrlGetVkey") || bodyText.contains("CgiGetVkey") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{"songmid":"song","result":104003,"purl":""}]
                    }}}
                """.trimIndent()
                else -> error("unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody.toResponseBody()).build()
        }.build()

        try {
            val stream = QQMusicClient(
                client = previewClient,
                authProvider = {
                    QQMusicAuthBundle(
                        cookies = mapOf(
                            "uin" to "10001",
                            "qm_keyst" to "private-key",
                            "tmeLoginType" to "2"
                        )
                    )
                }
            ).getPlaybackStream(
                songMid = "song",
                mediaMid = "media",
                preferredQuality = "standard",
                probeCandidates = true
            )

            assertEquals("preview", stream.qualityKey)
            assertTrue(stream.isPreviewClip)
            assertEquals("audio/mpeg", stream.mimeType)
            assertTrue(stream.url.contains("RS02songmedia.mp3"))
            assertTrue(
                previewRequests.any {
                    it.bodyText().contains("RS02preview-media.mp3")
                }
            )
            assertTrue(previewRequests.none { it.url.host.endsWith("stream.qqmusic.qq.com") })
        } finally {
            previewClient.dispatcher.executorService.shutdown()
            previewClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `download mode rejects preview-only tracks without requesting RS02`() = runTest {
        val downloadRequests = mutableListOf<Request>()
        val downloadClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            downloadRequests += request
            val bodyText = request.bodyText()
            val responseBody = when {
                bodyText.contains("GetCdnDispatch") -> """
                    {"code":0,"req_0":{"code":0,"data":{"sip":[]}}}
                """.trimIndent()
                bodyText.contains("UrlGetVkey") || bodyText.contains("CgiGetVkey") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{"songmid":"song","result":104003,"purl":""}]
                    }}}
                """.trimIndent()
                else -> error("unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody.toResponseBody()).build()
        }.build()

        try {
            val streamClient = QQMusicClient(
                client = downloadClient,
                authProvider = {
                    QQMusicAuthBundle(
                        cookies = mapOf(
                            "uin" to "10001",
                            "qm_keyst" to "private-key",
                            "tmeLoginType" to "2"
                        )
                    )
                }
            )
            assertThrows(java.io.IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    streamClient.getPlaybackStream(
                        songMid = "song",
                        mediaMid = "media",
                        preferredQuality = "standard",
                        probeCandidates = false,
                        allowPreview = false
                    )
                }
            }
            assertTrue(downloadRequests.none { it.bodyText().contains("RS02") })
        } finally {
            downloadClient.dispatcher.executorService.shutdown()
            downloadClient.connectionPool.evictAll()
        }
    }

    private fun Request.bodyText(): String {
        val body = body ?: return ""
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }
}
