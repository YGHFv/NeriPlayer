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
import org.junit.Assert.assertFalse
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

    @Test
    fun `master quality uses the full master media mid from Song vs index 3`() = runTest {
        val masterRequests = mutableListOf<Request>()
        val masterClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            masterRequests += request
            val bodyText = request.bodyText()
            val responseBody = when {
                bodyText.contains("get_song_detail_yqq") -> """
                    {"code":0,"req_0":{"code":0,"data":{"track_info":{
                      "mid":"song","name":"Master Track","singer":[{"name":"A"}],
                      "album":{"name":"Album","mid":"album"},"interval":240,
                      "file":{"media_mid":"media","size_new":[123456789]},
                      "vs":["preview-media","","","master-media"]
                    }}}}
                """.trimIndent()
                bodyText.contains("AI00master-media.flac") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{
                        "songmid":"song",
                        "filename":"AI00master-media.flac",
                        "result":0,
                        "purl":"AI00master-media.flac?vkey=master"
                      }]
                    }}}
                """.trimIndent()
                bodyText.contains("GetCdnDispatch") -> """
                    {"code":0,"req_0":{"code":0,"data":{"sip":[]}}}
                """.trimIndent()
                else -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{"songmid":"song","result":104003,"purl":""}]
                    }}}
                """.trimIndent()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody.toResponseBody()).build()
        }.build()

        try {
            val stream = QQMusicClient(
                client = masterClient,
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
                preferredQuality = "master",
                probeCandidates = false
            )

            assertEquals("master", stream.qualityKey)
            assertEquals("audio/flac", stream.mimeType)
            assertEquals("flac", stream.fileExtensionHint)
            assertEquals(
                "https://sip.stream.qqmusic.qq.com/AI00master-media.flac?vkey=master",
                stream.url
            )
            assertFalse(stream.isPreviewClip)
            assertTrue(
                masterRequests.any {
                    it.bodyText().contains("AI00master-media.flac")
                }
            )
        } finally {
            masterClient.dispatcher.executorService.shutdown()
            masterClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `missing master entitlement falls back to lossless`() = runTest {
        val fallbackRequests = mutableListOf<Request>()
        val fallbackClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            fallbackRequests += request
            val bodyText = request.bodyText()
            val responseBody = when {
                bodyText.contains("get_song_detail_yqq") -> """
                    {"code":0,"req_0":{"code":0,"data":{"track_info":{
                      "mid":"song","name":"Fallback","singer":[{"name":"A"}],
                      "album":{"name":"Album","mid":"album"},"interval":200,
                      "file":{"media_mid":"media","size_new":[123456789]},
                      "vs":["preview-media","","","master-media"]
                    }}}}
                """.trimIndent()
                bodyText.contains("F000songmedia.flac") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{
                        "songmid":"song",
                        "filename":"F000songmedia.flac",
                        "result":0,
                        "purl":"F000songmedia.flac?vkey=lossless"
                      }]
                    }}}
                """.trimIndent()
                bodyText.contains("GetCdnDispatch") -> """
                    {"code":0,"req_0":{"code":0,"data":{"sip":[]}}}
                """.trimIndent()
                else -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{"songmid":"song","result":104003,"purl":""}]
                    }}}
                """.trimIndent()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody.toResponseBody()).build()
        }.build()

        try {
            val stream = QQMusicClient(
                client = fallbackClient,
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
                preferredQuality = "master",
                probeCandidates = false
            )

            assertEquals("lossless", stream.qualityKey)
            assertEquals("audio/flac", stream.mimeType)
            assertTrue(
                fallbackRequests.any {
                    it.bodyText().contains("AI00master-media.flac")
                }
            )
        } finally {
            fallbackClient.dispatcher.executorService.shutdown()
            fallbackClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `hires quality uses the Song vs index 4 media mid`() = runTest {
        val requests = mutableListOf<Request>()
        val hiresClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val bodyText = request.bodyText()
            val responseBody = when {
                bodyText.contains("get_song_detail_yqq") -> """
                    {"code":0,"req_0":{"code":0,"data":{"track_info":{
                      "mid":"song","name":"Hi-Res Track","singer":[{"name":"A"}],
                      "album":{"name":"Album","mid":"album"},"interval":220,
                      "file":{"media_mid":"media","size_new":[1,2,3,4,5,6,7]},
                      "vs":["preview","","","master-media","hires-media"]
                    }}}}
                """.trimIndent()
                bodyText.contains("Q000hires-media.flac") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{
                        "songmid":"song",
                        "filename":"Q000hires-media.flac",
                        "result":0,
                        "purl":"Q000hires-media.flac?vkey=hires"
                      }]
                    }}}
                """.trimIndent()
                bodyText.contains("GetCdnDispatch") -> """
                    {"code":0,"req_0":{"code":0,"data":{"sip":[]}}}
                """.trimIndent()
                else -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{"songmid":"song","result":104003,"purl":""}]
                    }}}
                """.trimIndent()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody.toResponseBody()).build()
        }.build()

        try {
            val stream = QQMusicClient(
                client = hiresClient,
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
                preferredQuality = "hires",
                probeCandidates = false
            )

            assertEquals("hires", stream.qualityKey)
            assertEquals("audio/flac", stream.mimeType)
            assertTrue(requests.any { it.bodyText().contains("Q000hires-media.flac") })
        } finally {
            hiresClient.dispatcher.executorService.shutdown()
            hiresClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `DTS X quality uses the Song vs index 30 media mid`() = runTest {
        val requests = mutableListOf<Request>()
        val dtsClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val bodyText = request.bodyText()
            val responseBody = when {
                bodyText.contains("get_song_detail_yqq") -> """
                    {"code":0,"req_0":{"code":0,"data":{"track_info":{
                      "mid":"song","name":"DTS Track","singer":[{"name":"A"}],
                      "album":{"name":"Album","mid":"album"},"interval":240,
                      "file":{"media_mid":"media","size_new":[0,0,0,0,0,0,0,0,0,99]},
                      "vs":["preview","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","dts-media"]
                    }}}}
                """.trimIndent()
                bodyText.contains("DT03dts-media.mp4") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{
                        "songmid":"song",
                        "filename":"DT03dts-media.mp4",
                        "result":0,
                        "purl":"DT03dts-media.mp4?vkey=dts"
                      }]
                    }}}
                """.trimIndent()
                bodyText.contains("GetCdnDispatch") -> """
                    {"code":0,"req_0":{"code":0,"data":{"sip":[]}}}
                """.trimIndent()
                else -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "sip":["https://sip.stream.qqmusic.qq.com/"],
                      "midurlinfo":[{"songmid":"song","result":104003,"purl":""}]
                    }}}
                """.trimIndent()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody.toResponseBody()).build()
        }.build()

        try {
            val stream = QQMusicClient(
                client = dtsClient,
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
                preferredQuality = "dtsx",
                probeCandidates = false
            )

            assertEquals("dtsx", stream.qualityKey)
            assertEquals("audio/mp4", stream.mimeType)
            assertEquals("mp4", stream.fileExtensionHint)
            assertTrue(requests.any { it.bodyText().contains("DT03dts-media.mp4") })
        } finally {
            dtsClient.dispatcher.executorService.shutdown()
            dtsClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `home recommendations load song shelves and openable playlists`() = runTest {
        val recommendationRequests = mutableListOf<Request>()
        val recommendationClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            recommendationRequests += request
            val bodyText = request.bodyText()
            val responseBody = when {
                bodyText.contains("get_recommend_feed") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "load_mark":1,
                      "v_shelf":[{
                        "id":207,
                        "title_template":"For You · {String}",
                        "title_content":"Today",
                        "miscellany":{"songList":"101,102"},
                        "v_niche":[
                          {"v_card":[{"id":"101","type":200},{"id":"102","type":200}]},
                          {"v_card":[{
                            "id":"7001","type":500,"subtype":0,"jumptype":10014,
                            "title":"QQ Playlist","cover":"//cover.example/p.jpg",
                            "cnt":88
                          }]}
                        ]
                      }]
                    }}}
                """.trimIndent()
                bodyText.contains("CgiGetTrackInfo") -> """
                    {"code":0,"req_0":{"code":0,"data":{"tracks":[
                      {
                        "id":101,"mid":"mid-1","name":"First","singer":[{"name":"A"}],
                        "album":{"name":"Album","mid":"album-1"},"interval":180,
                        "file":{"media_mid":"media-1"}
                      },
                      {
                        "id":102,"mid":"mid-2","name":"Second","singer":[{"name":"B"}],
                        "album":{"name":"Album","mid":"album-2"},"interval":200,
                        "file":{"media_mid":"media-2"}
                      }
                    ]}}}
                """.trimIndent()
                bodyText.contains("GetRecommendFeed") -> """
                    {"code":0,"req_0":{"code":0,"data":{
                      "HasMore":false,
                      "FromLimit":2,
                      "List":[
                        {"Playlist":{"basic":{
                          "tid":"7001","title":"QQ Playlist",
                          "cover":{"default_url":"//cover.example/p.jpg"},
                          "song_cnt":12,"play_cnt":88
                        }}},
                        {"Playlist":{"basic":{
                          "tid":"7002","title":"Second Playlist",
                          "cover":{"default_url":"//cover.example/p2.jpg"},
                          "song_cnt":24,"play_cnt":99
                        }}}
                      ]
                    }}}
                """.trimIndent()
                else -> error("unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(responseBody.toResponseBody()).build()
        }.build()

        try {
            val feed = QQMusicClient(
                client = recommendationClient,
                authProvider = {
                    QQMusicAuthBundle(
                        cookies = mapOf(
                            "uin" to "10001",
                            "qm_keyst" to "private-key",
                            "tmeLoginType" to "2"
                        )
                    )
                }
            ).getHomeRecommendations()

            assertEquals(1, feed.shelves.size)
            assertEquals("For You · Today", feed.shelves.single().title)
            assertEquals(listOf("mid-1", "mid-2"), feed.shelves.single().songs.map { it.mid })
            assertEquals(2, feed.playlists.size)
            assertEquals("QQ Playlist", feed.playlists.first().name)
            assertEquals("https://cover.example/p.jpg", feed.playlists.first().coverUrl)
            assertEquals("Second Playlist", feed.playlists.last().name)
            assertEquals(24, feed.playlists.last().trackCount)
            val feedRequest = recommendationRequests.first().bodyText()
            assertTrue(feedRequest.contains("\"ct\":20"))
            assertTrue(feedRequest.contains("\"cv\":2276"))
            assertTrue(feedRequest.contains("\"platform\":\"wk_v17\""))
            val playlistRequest = recommendationRequests.first {
                it.bodyText().contains("GetRecommendFeed")
            }.bodyText()
            assertTrue(playlistRequest.contains("\"From\":0"))
            assertTrue(playlistRequest.contains("\"Size\":25"))
        } finally {
            recommendationClient.dispatcher.executorService.shutdown()
            recommendationClient.connectionPool.evictAll()
        }
    }

    private fun Request.bodyText(): String {
        val body = body ?: return ""
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }
}
