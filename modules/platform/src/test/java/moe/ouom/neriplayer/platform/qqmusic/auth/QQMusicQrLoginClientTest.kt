package moe.ouom.neriplayer.platform.qqmusic.auth

import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginChannel
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginStatus
import okhttp3.FormBody
import okhttp3.Interceptor
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
import org.junit.Test
import org.json.JSONObject

class QQMusicQrLoginClientTest {
    private val clients = mutableListOf<OkHttpClient>()

    @After
    fun tearDown() {
        clients.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `qq scan completes oauth exchange and builds reusable credentials`() {
        val requests = mutableListOf<Request>()
        var pollCount = 0
        val client = testClient { request ->
            requests += request
            when (request.url.host + request.url.encodedPath) {
                "xui.ptlogin2.qq.com/ssl/ptqrshow" -> {
                    assertEquals("716027609", request.url.queryParameter("appid"))
                    assertEquals("100497308", request.url.queryParameter("pt_3rd_aid"))
                    response(
                        request = request,
                        body = PNG_BYTES,
                        headers = listOf(
                            "Set-Cookie" to
                                "qrsig=fake-qrsig_01; Domain=ptlogin2.qq.com; Path=/; Max-Age=3600"
                        )
                    )
                }
                "xui.ptlogin2.qq.com/ssl/ptqrlogin" -> {
                    assertTrue(
                        request.header("Cookie").orEmpty().contains("qrsig=fake-qrsig_01")
                    )
                    assertEquals(
                        hash33("fake-qrsig_01", 0).toString(),
                        request.url.queryParameter("ptqrtoken")
                    )
                    pollCount += 1
                    val body = when (pollCount) {
                        1 -> "ptuiCB('66','0','','0','waiting','')"
                        2 -> "ptuiCB('67','0','','0','scanned','')"
                        else -> "ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?ptsigx=fake','0','ok','Listener')"
                    }
                    response(request, body)
                }
                "ssl.ptlogin2.graph.qq.com/check_sig" -> {
                    // check_sig issues graph.qq.com cookies and rejects a polluted QR cookie.
                    assertEquals(null, request.header("Cookie"))
                    assertEquals(
                        "https://xui.ptlogin2.qq.com/",
                        request.header("Referer")
                    )
                    response(
                        request = request,
                        body = "",
                        code = 302,
                        headers = listOf(
                            "Location" to "https://graph.qq.com/oauth2.0/login_jump",
                            "Set-Cookie" to
                                "p_skey=fake-pskey; Domain=graph.qq.com; Path=/; Max-Age=3600",
                            "Set-Cookie" to
                                "pt_oauth_token=fake-oauth; Domain=graph.qq.com; Path=/; Max-Age=3600"
                        )
                    )
                }
                "graph.qq.com/oauth2.0/login_jump" -> {
                    val cookies = request.header("Cookie").orEmpty()
                    assertTrue(cookies.contains("p_skey=fake-pskey"))
                    assertTrue(cookies.contains("pt_oauth_token=fake-oauth"))
                    response(
                        request = request,
                        body = "",
                        headers = listOf(
                            // Current QQ login_jump can issue the user identity cookie.
                            "Set-Cookie" to
                                "p_uin=o0010001; Domain=graph.qq.com; Path=/; Max-Age=3600"
                        )
                    )
                }
                "graph.qq.com/oauth2.0/authorize" -> {
                    val cookies = request.header("Cookie").orEmpty()
                    assertTrue(cookies.contains("p_uin=o0010001"))
                    assertTrue(cookies.contains("p_skey=fake-pskey"))
                    assertTrue(cookies.contains("pt_oauth_token=fake-oauth"))
                    val form = request.body as FormBody
                    val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals(
                        hash33("fake-pskey", 5381).toString(),
                        fields["g_tk"]
                    )
                    response(
                        request = request,
                        body = "",
                        code = 302,
                        headers = listOf(
                            "Location" to
                                "https://y.qq.com/portal/wx_redirect.html" +
                                "?code=ABCDEF0123456789ABCDEF0123456789&state=${fields["state"]}"
                        )
                    )
                }
                "u.y.qq.com/cgi-bin/musicu.fcg" -> {
                    val body = JSONObject(
                        Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
                    )
                    val req = body.getJSONObject("req")
                    assertEquals("QQConnectLogin.LoginServer", req.getString("module"))
                    assertEquals("QQLogin", req.getString("method"))
                    assertEquals(2, body.getJSONObject("comm").getInt("tmeLoginType"))
                    assertEquals(
                        "ABCDEF0123456789ABCDEF0123456789",
                        req.getJSONObject("param").getString("code")
                    )
                    response(
                        request,
                        """{"code":0,"req":{"code":0,"data":{""" +
                            """"musicid":10001,"musickey":"fake-key","loginType":2,""" +
                            """"musickeyCreateTime":1760000000,"keyExpiresIn":259200,""" +
                            """"encryptUin":"encrypted","nick":"Listener"}}}"""
                    )
                }
                else -> error("Unexpected QQ QR request: ${request.url}")
            }
        }

        val qrClient = QQMusicQrLoginClient(client, clock = { 1_760_000_000_000L })
        val session = qrClient.createSession(QQMusicQrLoginChannel.QQ)
        assertEquals(QQMusicQrLoginChannel.QQ, session.channel)
        assertTrue(PNG_BYTES.contentEquals(session.imageBytes))

        assertEquals(
            QQMusicQrLoginStatus.WAITING,
            qrClient.checkLogin(session).status
        )
        assertEquals(
            QQMusicQrLoginStatus.SCANNED,
            qrClient.checkLogin(session).status
        )
        val confirmed = qrClient.checkLogin(session)
        assertEquals(QQMusicQrLoginStatus.CONFIRMED, confirmed.status)
        assertEquals("10001", confirmed.cookies["uin"])
        assertEquals("10001", confirmed.cookies["qqmusic_uin"])
        assertEquals("fake-key", confirmed.cookies["qm_keyst"])
        assertEquals("fake-key", confirmed.cookies["qqmusic_key"])
        assertEquals("1", confirmed.cookies["login_type"])
        assertEquals("2", confirmed.cookies["tmeLoginType"])
        assertEquals("encrypted", confirmed.cookies["euin"])
        assertEquals(
            "1760259200",
            confirmed.cookies["psrf_access_token_expiresAt"]
        )
        assertEquals(
            QQMusicQrLoginStatus.EXPIRED,
            qrClient.checkLogin(session).status
        )
        assertTrue(requests.none { it.method == "POST" && it.url.host == "u.y.qq.com" &&
            it.header("Cookie").orEmpty().contains("qrsig") })
    }

    @Test
    fun `wechat scan bootstraps device and exchanges login code`() {
        val requests = mutableListOf<Request>()
        var wechatAppId: String? = null
        var pollCount = 0
        val client = testClient { request ->
            requests += request
            when (request.url.host + request.url.encodedPath) {
                "api.tencentmusic.com/tme/trpc/proxy" -> {
                    assertEquals("GetQimei", request.header("Method"))
                    assertEquals("QQMusic", request.header("User-Agent"))
                    response(
                        request,
                        "{\"code\":0,\"data\":\"{\\\"code\\\":0,\\\"data\\\":" +
                            "{\\\"q16\\\":\\\"device-q16\\\",\\\"q36\\\":\\\"device-q36\\\"}}\"}"
                    )
                }
                "open.weixin.qq.com/connect/qrconnect" -> {
                    wechatAppId = request.url.queryParameter("appid")
                    assertTrue(wechatAppId.orEmpty().matches(Regex("wx[0-9a-f]{16}")))
                    response(
                        request,
                        """<img src="/connect/qrcode/uuid-fixture"><a href="https://open.weixin.qq.com/connect/confirm?uuid=uuid-fixture">""",
                        headers = listOf(
                            "Set-Cookie" to
                                "wx_session=web-cookie; Domain=weixin.qq.com; Path=/; Max-Age=3600"
                        )
                    )
                }
                "open.weixin.qq.com/connect/qrcode/uuid-fixture" -> response(request, PNG_BYTES)
                "lp.open.weixin.qq.com/connect/l/qrconnect" -> {
                    assertTrue(request.header("Cookie").orEmpty().contains("wx_session=web-cookie"))
                    if (pollCount >= 2) {
                        assertEquals("404", request.url.queryParameter("last"))
                    }
                    val statuses = listOf("408", "404", "408", "405")
                    val body =
                        "window.wx_errcode=${statuses[pollCount]};window.wx_code='private-code';"
                    pollCount += 1
                    response(request, body)
                }
                "u.y.qq.com/cgi-bin/musicu.fcg" -> {
                    val body = JSONObject(
                        Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
                    )
                    val req = body.optJSONObject("req")
                        ?: body.getJSONObject("req_0")
                    if (req.getString("method") == "GetSession") {
                        response(
                            request,
                            """{"code":0,"req_0":{"code":0,"data":{"session":{"uid":123,"sid":"session-sid"}}}}"""
                        )
                    } else {
                        assertEquals("music.login.LoginServer", req.getString("module"))
                        assertEquals("Login", req.getString("method"))
                        assertEquals(
                            wechatAppId,
                            req.getJSONObject("param").getString("strAppid")
                        )
                        assertEquals(1, body.getJSONObject("comm").getInt("tmeLoginType"))
                        response(
                            request,
                            """{"code":0,"req":{"code":0,"data":{""" +
                                """"musicid":0,"str_musicid":"1152921504606846976",""" +
                                """"musickey":"wechat-key","loginType":1,""" +
                                """"openid":"open","refresh_token":"refresh",""" +
                                """"unionid":"union","refresh_key":"refresh-key"}}}"""
                        )
                    }
                }
                else -> error("Unexpected WeChat QR request: ${request.url}")
            }
        }

        val qrClient = QQMusicQrLoginClient(client, clock = { 1_760_000_000_000L })
        val session = qrClient.createSession(QQMusicQrLoginChannel.WECHAT)
        assertEquals(QQMusicQrLoginChannel.WECHAT, session.channel)
        assertEquals(
            QQMusicQrLoginStatus.WAITING,
            qrClient.checkLogin(session).status
        )
        assertEquals(
            QQMusicQrLoginStatus.SCANNED,
            qrClient.checkLogin(session).status
        )
        assertEquals(
            QQMusicQrLoginStatus.SCANNED,
            qrClient.checkLogin(session).status
        )
        val confirmed = qrClient.checkLogin(session)
        assertEquals(QQMusicQrLoginStatus.CONFIRMED, confirmed.status)
        assertEquals("1152921504606846976", confirmed.cookies["uin"])
        assertEquals("1152921504606846976", confirmed.cookies["wxuin"])
        assertEquals("2", confirmed.cookies["login_type"])
        assertEquals("1", confirmed.cookies["tmeLoginType"])
        assertEquals("wechat-key", confirmed.cookies["qqmusic_key"])
        assertEquals("open", confirmed.cookies["wxopenid"])
        assertEquals("refresh", confirmed.cookies["wxrefresh_token"])
        assertEquals("refresh-key", confirmed.cookies["wxrefresh_key"])
        assertTrue(requests.any { it.url.host == "api.tencentmusic.com" })
    }

    @Test
    fun `ptui parser decodes escapes and rejects executable payloads`() {
        val values = parsePtuiCallback(
            """ptuiCB('67','0','','0','it\'s scanned','\u4f60\x21');"""
        )
        assertEquals("67", values[0])
        assertEquals("it's scanned", values[4])
        assertEquals("你!", values[5])

        listOf(
            "alert(1)",
            "ptuiCB('66')",
            "ptuiCB('66','0','','0','waiting','');alert(1)",
            "ptuiCB('66','0','','0','waiting','',)"
        ).forEach { payload ->
            val failed = runCatching { parsePtuiCallback(payload) }.isFailure
            assertTrue("accepted malformed payload: $payload", failed)
        }
    }

    @Test
    fun `qq refusal status is reported as a retryable refusal`() {
        val client = testClient { request ->
            when (request.url.host + request.url.encodedPath) {
                "xui.ptlogin2.qq.com/ssl/ptqrshow" -> response(
                    request = request,
                    body = PNG_BYTES,
                    headers = listOf(
                        "Set-Cookie" to
                            "qrsig=fake-qrsig_01; Domain=ptlogin2.qq.com; Path=/; Max-Age=3600"
                    )
                )
                "xui.ptlogin2.qq.com/ssl/ptqrlogin" ->
                    response(request, "ptuiCB('68','0','','0','cancelled','')")
                else -> error("Unexpected QQ QR request: ${request.url}")
            }
        }
        val qrClient = QQMusicQrLoginClient(client, clock = { 1_760_000_000_000L })
        val session = qrClient.createSession(QQMusicQrLoginChannel.QQ)

        val result = qrClient.checkLogin(session)

        assertEquals(QQMusicQrLoginStatus.REFUSED, result.status)
        assertEquals("cancelled", result.message)
        assertEquals(
            QQMusicQrLoginStatus.EXPIRED,
            qrClient.checkLogin(session).status
        )
    }

    @Test
    fun `login credential parser accepts metadata strings and enforces login type`() {
        val credential = parseQqMusicLoginCredential(
            """{"code":0,"req":{"code":0,"data":{""" +
                """"musicid":"10001","musickey":"private-key","loginType":"2",""" +
                """"musickeyCreateTime":"1760000000","keyExpiresIn":"259200"}}}""",
            expectedLoginType = 2
        )
        assertEquals("10001", credential.musicId)
        assertEquals(1_760_000_000L, credential.createdAt)
        assertEquals(259_200L, credential.expiresIn)

        val mismatch = runCatching {
            parseQqMusicLoginCredential(
                """{"code":0,"req":{"code":0,"data":{""" +
                    """"musicid":10001,"musickey":"private-key","loginType":1}}}""",
                expectedLoginType = 2
            )
        }
        assertTrue(mismatch.isFailure)
        assertFalse(mismatch.exceptionOrNull()?.message.orEmpty().contains("private-key"))
    }

    @Test
    fun `hash33 remains unsigned across long qr signatures`() {
        val hash = hash33("a".repeat(512), 5381)
        assertTrue(hash in 0..0x7fff_ffffL)
    }

    private fun testClient(handler: (Request) -> Response): OkHttpClient {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        clients += client
        return client
    }

    private fun response(
        request: Request,
        body: String,
        code: Int = 200,
        headers: List<Pair<String, String>> = emptyList()
    ): Response = response(
        request = request,
        body = body.toByteArray(),
        code = code,
        headers = headers
    )

    private fun response(
        request: Request,
        body: ByteArray,
        code: Int = 200,
        headers: List<Pair<String, String>> = emptyList()
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Found")
            .body(body.toResponseBody(null))
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.build()
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x01,
            0x02,
            0x03
        )
    }
}
