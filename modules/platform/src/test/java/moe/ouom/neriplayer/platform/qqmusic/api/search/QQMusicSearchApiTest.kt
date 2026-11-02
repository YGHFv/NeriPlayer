package moe.ouom.neriplayer.platform.qqmusic.api.search

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class QQMusicSearchApiTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body) = replies.removeFirst()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()
    private val api = QQMusicSearchApi(client)

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `search maps songs and treats missing sections as no results`() = runTest {
        replies += 200 to """
            {"code":0,"req_0":{"code":0,"data":{"body":{"song":{"list":[
              {"id":485,"mid":"mid-1","name":"Signal","singer":[{"name":"A"},{"name":"B"}],"album":{"mid":"alb","name":"Album"},"interval":185,"strMediaMid":"fallback-media","file":{"media_mid":"media-1"}},
              {"mid":"mid-2","name":"Single","singer":[],"album":{},"interval":59}
            ]}},"meta":{"sum":2,"is_filter":0}}}}
        """.trimIndent()
        repeat(3) {
            replies += 200 to """
                {"code":0,"req_0":{"code":0,"data":{"body":{"song":{"list":[]}},"meta":{"sum":0,"is_filter":0}}}}
            """.trimIndent()
        }

        assertEquals(
            listOf(
                SongSearchInfo(
                    id = "mid-1",
                    platformId = "485",
                    songName = "Signal",
                    singer = "A/B",
                    duration = "3:05",
                    source = MusicPlatform.QQ_MUSIC,
                    albumName = "Album",
                    coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000alb.jpg",
                    mediaId = "media-1"
                ),
                SongSearchInfo("mid-2", "Single", "", "0:59", MusicPlatform.QQ_MUSIC, null, null)
            ),
            api.search("Signal", page = 2)
        )
        repeat(3) { assertTrue(api.search("Signal", page = 1).isEmpty()) }
        val request = requests.first()
        assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("tmeLoginType=-1", request.header("Cookie"))
        val body = request.bodyJson()
        val comm = body.getJSONObject("comm")
        assertEquals(11, comm.getInt("ct"))
        assertEquals("0", comm.getString("uin"))
        val searchRequest = body.getJSONObject("req_0")
        assertEquals("DoSearchForQQMusicDesktop", searchRequest.getString("method"))
        val param = searchRequest.getJSONObject("param")
        assertEquals("Signal", param.getString("query"))
        assertEquals(0, param.getInt("search_type"))
        assertEquals(20, param.getInt("num_per_page"))
        assertEquals(2, param.getInt("page_num"))
    }

    @Test
    fun `desktop failure falls back to legacy and pins pagination to it`() = runTest {
        replies += 500 to "busy"
        replies += 200 to """
            {"code":0,"data":{"song":{"totalnum":1,"list":[
              {"songid":485,"songmid":"mid-1","songname":"Signal","singer":[{"name":"A"}],"albummid":"alb","albumname":"Album","interval":185,"file":{"media_mid":"media-1"}}
            ]}}}
        """.trimIndent()
        replies += 200 to """{"code":0,"data":{"song":{"totalnum":1,"list":[]}}}"""

        assertEquals(1, api.search("Signal", page = 1).size)
        assertTrue(api.search("Signal", page = 2).isEmpty())

        assertEquals(3, requests.size)
        assertEquals("/cgi-bin/musicu.fcg", requests[0].url.encodedPath)
        assertEquals("/soso/fcgi-bin/client_search_cp", requests[1].url.encodedPath)
        assertEquals("/soso/fcgi-bin/client_search_cp", requests[2].url.encodedPath)
        val legacyUrl = requests[2].url
        assertEquals("2", legacyUrl.queryParameter("p"))
        assertEquals("0", legacyUrl.queryParameter("t"))
        assertEquals("1", legacyUrl.queryParameter("new_json"))
    }

    @Test
    fun `pagination stays on the desktop source after the first successful page`() = runTest {
        replies += 200 to """
            {"code":0,"req_0":{"code":0,"data":{"body":{"song":{"list":[]}},"meta":{"sum":60,"is_filter":0}}}}
        """.trimIndent()
        replies += 500 to "busy"

        assertTrue(api.search("Signal", page = 1).isEmpty())
        val error = assertThrows(IOException::class.java) {
            runBlocking { api.search("Signal", page = 2) }
        }

        assertTrue(error.message.orEmpty().contains("HTTP 500"))
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.encodedPath == "/cgi-bin/musicu.fcg" })
    }

    @Test
    fun `song metadata converts the interval to milliseconds`() = runTest {
        replies += 200 to detail(interval = 180)
        replies += 200 to detail(interval = 0)

        val metadata = api.getSongMetadata("mid-1")

        assertEquals(180_000L, metadata.durationMs)
        assertEquals("Signal", metadata.details.songName)
        assertEquals("A/B", metadata.details.singer)
        assertEquals("Album", metadata.details.album)
        assertEquals("https://y.qq.com/music/photo_new/T002R800x800M000alb.jpg", metadata.details.coverUrl)
        assertNull(metadata.details.lyric)
        assertEquals(0L, api.getSongMetadata("mid-1").durationMs)
        assertTrue(requests.first().url.queryParameter("data")!!.contains(""""song_mid":"mid-1""""))
    }

    @Test
    fun `song metadata fails when the detail payload is missing`() {
        replies += 200 to """{"other":{}}"""
        replies += 200 to """{"songinfo":{"data":null}}"""
        replies += 200 to """{"songinfo":{"data":{"track_info":null}}}"""
        replies += 500 to "busy"

        assertEquals("响应中找不到 songinfo 字段", metadataFailure().message)
        assertEquals("找不到ID为 mid-1 的歌曲详情", metadataFailure().message)
        assertEquals("找不到ID为 mid-1 的歌曲详情", metadataFailure().message)
        assertEquals("HTTP 500", metadataFailure().message)
    }

    @Test
    fun `native lyrics decode both tracks or quietly give up`() = runTest {
        replies += 200 to """{"req":{"code":0,"data":{"lyric":"[00:01.00]hello","trans":"[00:01.00]你好"}}}"""
        replies += 200 to """{"req":{"code":0}}"""
        replies += 200 to """{"req":{"code":-1}}"""
        replies += 200 to "{}"
        replies += 500 to "busy"

        assertEquals("[00:01.00]hello" to "[00:01.00]你好", api.getNativeLyrics("mid-1"))
        repeat(4) { assertEquals(null to null, api.getNativeLyrics("mid-1")) }
        assertEquals("https://y.qq.com/", requests.first().header("Referer"))
        assertEquals("json", requests.first().url.queryParameter("format"))
    }

    @Test
    fun `native song info combines details with lyrics`() = runTest {
        replies += 200 to detail(interval = 180)
        replies += 200 to """{"req":{"code":0,"data":{"lyric":"[00:01.00]hello"}}}"""

        val details = api.getSongInfo("mid-1")

        assertEquals("mid-1", details.id)
        assertEquals("[00:01.00]hello", details.lyric)
        assertNull(details.translatedLyric)
    }

    @Test
    fun `authenticated requests carry cookies and musicu auth fields`() = runTest {
        val auth = QQMusicAuthBundle(
            cookies = mapOf(
                "uin" to "123456",
                "qqmusic_key" to "music-key",
                "qm_keyst" to "music-key",
                "login_type" to "2"
            ),
            savedAt = 42L
        )
        val authenticatedApi = QQMusicSearchApi(client) { auth }
        replies += 200 to """
            {"code":0,"req_0":{"code":0,"data":{"body":{"song":{"list":[]}},"meta":{"sum":0,"is_filter":0}}}}
        """.trimIndent()
        replies += 200 to detail(interval = 90)
        replies += 200 to """{"req":{"code":0,"data":{}}}"""

        authenticatedApi.search("Signal", page = 1)
        authenticatedApi.getSongInfo("mid-1")

        val searchRequest = requests[0]
        assertEquals(
            "uin=123456; qqmusic_key=music-key; qm_keyst=music-key; login_type=2",
            searchRequest.header("Cookie")
        )
        val searchBody = searchRequest.bodyJson()
        val searchComm = searchBody.getJSONObject("comm")
        assertEquals("123456", searchComm.getString("uin"))
        assertEquals("music-key", searchComm.getString("authst"))
        assertEquals(11, searchComm.getInt("ct"))

        val detailData = requests[1].url.queryParameter("data").orEmpty()
        assertTrue(detailData.contains(""""uin":"123456""""))
        assertTrue(detailData.contains(""""qq":"123456""""))
        assertTrue(detailData.contains(""""authst":"music-key""""))
        assertTrue(detailData.contains(""""tmeLoginType":2"""))
        assertTrue(detailData.contains(""""song_mid":"mid-1""""))
    }

    private fun metadataFailure(): IOException =
        assertThrows(IOException::class.java) { runBlocking { api.getSongMetadata("mid-1") } }

    private fun detail(interval: Int) = """
        {"songinfo":{"data":{"track_info":{"mid":"mid-1","name":"Signal","singer":[{"name":"A"},{"name":"B"}],
        "album":{"name":"Album","mid":"alb"},"interval":$interval}}}}
    """.trimIndent()

    private fun Request.bodyJson(): JSONObject {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
}
