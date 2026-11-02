package moe.ouom.neriplayer.platform.qqmusic.auth

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginChannel
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginCheckResult
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginSession
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginStatus
import moe.ouom.neriplayer.network.proxy.DynamicProxySelector
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val QQ_MUSIC_QR_LOG_TAG = "NERI-QQMusicQrClient"
private const val QQ_QR_SHOW_URL = "https://xui.ptlogin2.qq.com/ssl/ptqrshow"
private const val QQ_QR_CHECK_URL = "https://xui.ptlogin2.qq.com/ssl/ptqrlogin"
private const val QQ_LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump"
private const val QQ_CHECK_SIG_HOST = "ssl.ptlogin2.graph.qq.com"
private const val QQ_CHECK_SIG_PATH = "/check_sig"
private const val QQ_PTLOGIN_REFERER = "https://xui.ptlogin2.qq.com/"
private const val QQ_MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
private const val WECHAT_CONNECT_URL = "https://open.weixin.qq.com/connect/qrconnect"
private const val WECHAT_QR_BASE_URL = "https://open.weixin.qq.com/connect/qrcode/"
private const val WECHAT_POLL_URL = "https://lp.open.weixin.qq.com/connect/l/qrconnect"
private const val WECHAT_APP_ID = "wx48db31d50e334801"
private const val QIMEI_URL = "https://api.tencentmusic.com/tme/trpc/proxy"
private const val QQ_QR_SESSION_LIFETIME_MS = 120_000L
private const val QQ_QR_MAX_IMAGE_BYTES = 64L * 1024L
private const val QQ_QR_MAX_TEXT_BYTES = 256L * 1024L
private const val QQ_QR_WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
private const val QQ_LOGIN_USER_AGENT = "QQMusic"
private const val QQ_OAUTH_CODE_PATTERN = "^[A-F0-9]{32}$"
private val WECHAT_UUID_REGEX = Regex("(?:uuid=|/connect/qrcode/)([A-Za-z0-9_-]+)")
private val WECHAT_STATUS_REGEX =
    Regex("""window\.wx_errcode\s*=\s*(\d+)\s*;\s*window\.wx_code\s*=\s*'([^'\r\n]*)'""")
private val PNG_OR_JPEG_SIGNATURE = listOf(
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
    "GIF89a".toByteArray(StandardCharsets.US_ASCII),
    "GIF87a".toByteArray(StandardCharsets.US_ASCII)
)

class QQMusicQrLoginClient internal constructor(
    private val baseClient: OkHttpClient,
    private val clock: () -> Long
) {
    constructor() : this(defaultQrHttpClient(), System::currentTimeMillis)

    private val random = SecureRandom()
    private val stateLock = Any()
    private val states = LinkedHashMap<String, QrState>()

    @Throws(IOException::class)
    fun createSession(
        channel: QQMusicQrLoginChannel = QQMusicQrLoginChannel.QQ
    ): QQMusicQrLoginSession {
        clearExpiredStates()
        return when (channel) {
            QQMusicQrLoginChannel.QQ -> createQqSession()
            QQMusicQrLoginChannel.WECHAT -> createWechatSession()
        }
    }

    @Throws(IOException::class)
    fun checkLogin(session: QQMusicQrLoginSession): QQMusicQrLoginCheckResult {
        val state = synchronized(stateLock) { states[session.id] }
        if (state == null || state.channel != session.channel) {
            return QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.EXPIRED)
        }
        if (clock() >= state.expiresAt) {
            cancel(session.id)
            return QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.EXPIRED)
        }
        return when (state) {
            is QrState.Qq -> checkQqLogin(state)
            is QrState.Wechat -> checkWechatLogin(state)
        }
    }

    fun cancel(sessionId: String) {
        synchronized(stateLock) {
            states.remove(sessionId)
        }
    }

    fun reset() {
        synchronized(stateLock) {
            states.clear()
        }
    }

    private fun createQqSession(): QQMusicQrLoginSession {
        val jar = MemoryCookieJar()
        val nonce = randomHex(8)
        val url = QQ_QR_SHOW_URL.toHttpUrl().newBuilder()
            .addQueryParameter("appid", "716027609")
            .addQueryParameter("e", "2")
            .addQueryParameter("l", "M")
            .addQueryParameter("s", "3")
            .addQueryParameter("d", "72")
            .addQueryParameter("v", "4")
            .addQueryParameter("t", nonce)
            .addQueryParameter("daid", "383")
            .addQueryParameter("pt_3rd_aid", "100497308")
            .addQueryParameter("u1", QQ_LOGIN_JUMP)
            .build()
        val response = execute(
            client = baseClient,
            request = Request.Builder()
                .url(url)
                .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                .get()
                .build(),
            maxBytes = QQ_QR_MAX_IMAGE_BYTES,
            jar = jar,
            includeCookies = true
        )
        if (response.code != 200 || !looksLikeImage(response.body)) {
            throw IOException("QQ Music QR image response is invalid (HTTP ${response.code})")
        }
        val qrsig = jar.cookieValue("qrsig")
            ?: response.setCookieValue("qrsig")
            ?: throw IOException("QQ Music QR signature is missing")
        val session = newSession(
            channel = QQMusicQrLoginChannel.QQ,
            imageBytes = response.body
        )
        synchronized(stateLock) {
            states[session.id] = QrState.Qq(
                id = session.id,
                expiresAt = session.expiresAtEpochMs,
                jar = jar,
                qrsig = qrsig
            )
        }
        NPLogger.d(
            QQ_MUSIC_QR_LOG_TAG,
            "Created QQ QR session id=${session.id.redacted()} qrsigLength=${qrsig.length}"
        )
        return session
    }

    private fun checkQqLogin(state: QrState.Qq): QQMusicQrLoginCheckResult {
        val url = QQ_QR_CHECK_URL.toHttpUrl().newBuilder()
            .addQueryParameter("u1", QQ_LOGIN_JUMP)
            .addQueryParameter("from_ui", "1")
            .addQueryParameter("aid", "716027609")
            .addQueryParameter("daid", "383")
            .addQueryParameter("pt_3rd_aid", "100497308")
            .addQueryParameter("ptqrtoken", hash33(state.qrsig, 0).toString())
            .build()
        val response = execute(
            client = baseClient,
            request = Request.Builder()
                .url(url)
                .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                .get()
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES,
            jar = state.jar,
            includeCookies = true
        )
        if (response.code != 200) {
            throw IOException("QQ QR status request failed with HTTP ${response.code}")
        }
        val args = parsePtuiCallback(response.body.toString(StandardCharsets.UTF_8))
        return when (args.firstOrNull()) {
            "66" -> QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.WAITING)
            "67" -> QQMusicQrLoginCheckResult(
                QQMusicQrLoginStatus.SCANNED,
                message = args.getOrNull(4).orEmpty()
            )
            "65" -> {
                cancel(state.id)
                QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.EXPIRED)
            }
            "68" -> {
                cancel(state.id)
                QQMusicQrLoginCheckResult(
                    QQMusicQrLoginStatus.REFUSED,
                    message = args.getOrNull(4).orEmpty()
                )
            }
            "0" -> {
                val checkSigUrl = args.getOrNull(2)
                    ?.takeIf { trustedQqLoginUrl(it, QQ_CHECK_SIG_HOST, QQ_CHECK_SIG_PATH) != null }
                    ?: throw IOException("QQ returned an invalid check_sig URL")
                val credential = exchangeQqLogin(state, checkSigUrl)
                if (!completeState(state.id, state)) {
                    return QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.EXPIRED)
                }
                QQMusicQrLoginCheckResult(
                    status = QQMusicQrLoginStatus.CONFIRMED,
                    message = args.getOrNull(4).orEmpty(),
                    cookies = credential.toAuthenticatedCookies()
                )
            }
            else -> throw IOException(
                "QQ QR login returned unsupported status ${args.firstOrNull().orEmpty()}"
            )
        }
    }

    private fun exchangeQqLogin(
        state: QrState.Qq,
        rawCheckSigUrl: String
    ): QQCredential {
        val client = baseClient
        val checkSigUrl = trustedQqLoginUrl(
            rawCheckSigUrl,
            QQ_CHECK_SIG_HOST,
            QQ_CHECK_SIG_PATH
        ) ?: throw IOException("QQ check_sig URL is not trusted")
        val checkSig = execute(
            client = client,
            request = Request.Builder()
                .url(checkSigUrl)
                .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                .header("Referer", QQ_PTLOGIN_REFERER)
                .get()
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES,
            jar = state.jar
        )
        if (checkSig.code != 302) {
            throw IOException("QQ check_sig did not redirect (HTTP ${checkSig.code})")
        }
        val jumpUrl = trustedQqLoginUrl(
            checkSig.location,
            "graph.qq.com",
            "/oauth2.0/login_jump"
        ) ?: throw IOException("QQ login redirect is not trusted")
        NPLogger.d(
            QQ_MUSIC_QR_LOG_TAG,
            "QQ check_sig redirected cookieNames=${checkSig.setCookieNames()}"
        )

        val jump = execute(
            client = client,
            request = Request.Builder()
                .url(jumpUrl)
                .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                .get()
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES,
            jar = state.jar,
            includeCookies = true
        )
        if (jump.code !in 200..299) {
            throw IOException("QQ login_jump failed with HTTP ${jump.code}")
        }
        NPLogger.d(
            QQ_MUSIC_QR_LOG_TAG,
            "QQ login_jump cookieNames=${jump.setCookieNames()}"
        )
        val pUin = checkSig.setCookieValue("p_uin")
            ?: jump.setCookieValue("p_uin")
            ?: state.jar.cookieValue("p_uin")
            ?: throw IOException("QQ authorization cookie p_uin is missing")
        val pSkey = checkSig.setCookieValue("p_skey")
            ?: jump.setCookieValue("p_skey")
            ?: state.jar.cookieValue("p_skey")
            ?: throw IOException("QQ authorization cookie p_skey is missing")
        val graphCookies = linkedMapOf(
            "p_uin" to pUin,
            "p_skey" to pSkey
        )
        (checkSig.setCookieValue("pt_oauth_token")
            ?: jump.setCookieValue("pt_oauth_token")
            ?: state.jar.cookieValue("pt_oauth_token"))?.let { token ->
            graphCookies["pt_oauth_token"] = token
        }

        val oauthState = randomHex(16)
        val ui = randomHex(16).let { value ->
            "${value.take(8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-" +
                "${value.substring(16, 20)}-${value.substring(20)}"
        }
        val form = FormBody.Builder()
            .add("response_type", "code")
            .add("client_id", "100497308")
            .add(
                "redirect_uri",
                "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/"
            )
            .add("scope", "get_user_info,get_app_friends")
            .add("state", oauthState)
            .add("switch", "")
            .add("from_ptlogin", "1")
            .add("src", "1")
            .add("update_auth", "1")
            .add("openapi", "1010_1030")
            .add("g_tk", hash33(pSkey, 5381).toString())
            .add("auth_time", clock().toString())
            .add("ui", ui)
            .build()
        val authorize = execute(
            client = client,
            request = Request.Builder()
                .url("https://graph.qq.com/oauth2.0/authorize")
                .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                .header("Cookie", graphCookies.toCookieHeader())
                .post(form)
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES,
            jar = state.jar
        )
        if (authorize.code != 302) {
            throw IOException("QQ authorization did not redirect (HTTP ${authorize.code})")
        }
        val callback = authorize.location?.toHttpUrlOrNull()
            ?: throw IOException("QQ authorization callback is missing")
        if (
            callback.scheme != "https" ||
            callback.host != "y.qq.com" ||
            callback.encodedPath != "/portal/wx_redirect.html"
        ) {
            throw IOException("QQ authorization callback is not trusted")
        }
        val codes = callback.queryParameterValues("code")
        val callbackStates = callback.queryParameterValues("state")
        val code = codes.singleOrNull()
            ?.takeIf { it.matches(Regex(QQ_OAUTH_CODE_PATTERN)) }
            ?: throw IOException("QQ authorization code is invalid")
        if (callbackStates.singleOrNull() != oauthState) {
            throw IOException("QQ authorization state does not match")
        }

        val body = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("g_tk", 5381)
                    .put("platform", "yqq")
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("tmeLoginType", 2)
            )
            .put(
                "req",
                JSONObject()
                    .put("module", "QQConnectLogin.LoginServer")
                    .put("method", "QQLogin")
                    .put("param", JSONObject().put("code", code))
            )
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val login = execute(
            client = client,
            request = Request.Builder()
                .url(QQ_MUSICU_URL)
                .header("User-Agent", QQ_LOGIN_USER_AGENT)
                .post(body)
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES
        )
        if (login.code !in 200..299) {
            throw IOException("QQ Music credential exchange failed with HTTP ${login.code}")
        }
        return parseQqMusicLoginCredential(
            raw = login.body.toString(StandardCharsets.UTF_8),
            expectedLoginType = 2
        )
    }

    private fun createWechatSession(): QQMusicQrLoginSession {
        val device = bootstrapWechatDevice()
        val jar = MemoryCookieJar()
        val connectUrl = WECHAT_CONNECT_URL.toHttpUrl().newBuilder()
            .addQueryParameter("appid", WECHAT_APP_ID)
            .addQueryParameter(
                "redirect_uri",
                "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/"
            )
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", "snsapi_login")
            .addQueryParameter("state", "STATE")
            .addQueryParameter(
                "href",
                "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect"
            )
            .build()
        val connect = execute(
            client = baseClient,
            request = Request.Builder()
                .url(connectUrl)
                .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                .get()
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES,
            jar = jar,
            includeCookies = true
        )
        if (connect.code != 200) {
            throw IOException("WeChat QR page failed with HTTP ${connect.code}")
        }
        val uuid = WECHAT_UUID_REGEX.find(
            connect.body.toString(StandardCharsets.UTF_8)
        )?.groupValues?.getOrNull(1)
            ?: throw IOException("WeChat QR identifier is missing")
        val image = execute(
            client = baseClient,
            request = Request.Builder()
                .url(WECHAT_QR_BASE_URL + uuid)
                .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                .header("Referer", WECHAT_CONNECT_URL)
                .get()
                .build(),
            maxBytes = QQ_QR_MAX_IMAGE_BYTES,
            jar = jar,
            includeCookies = true
        )
        if (image.code != 200 || !looksLikeImage(image.body)) {
            throw IOException("WeChat QR image response is invalid (HTTP ${image.code})")
        }
        val session = newSession(
            channel = QQMusicQrLoginChannel.WECHAT,
            imageBytes = image.body
        )
        synchronized(stateLock) {
            states[session.id] = QrState.Wechat(
                id = session.id,
                expiresAt = session.expiresAtEpochMs,
                jar = jar,
                uuid = uuid,
                device = device
            )
        }
        NPLogger.d(
            QQ_MUSIC_QR_LOG_TAG,
            "Created WeChat QR session id=${session.id.redacted()} uuid=${uuid.redacted()}"
        )
        return session
    }

    private fun checkWechatLogin(state: QrState.Wechat): QQMusicQrLoginCheckResult {
        val scanned = synchronized(state) { state.scanned }
        val urlBuilder = WECHAT_POLL_URL.toHttpUrl().newBuilder()
            .addQueryParameter("uuid", state.uuid)
            .addQueryParameter("_", clock().toString())
        if (scanned) {
            urlBuilder.addQueryParameter("last", "404")
        }
        val response = try {
            execute(
                client = baseClient,
                request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("User-Agent", QQ_QR_WEB_USER_AGENT)
                    .header("Referer", "https://open.weixin.qq.com/")
                    .get()
                    .build(),
                maxBytes = QQ_QR_MAX_TEXT_BYTES,
                jar = state.jar,
                includeCookies = true
            )
        } catch (timeout: SocketTimeoutException) {
            return QQMusicQrLoginCheckResult(
                if (scanned) QQMusicQrLoginStatus.SCANNED else QQMusicQrLoginStatus.WAITING
            )
        }
        if (response.code != 200) {
            throw IOException("WeChat QR status request failed with HTTP ${response.code}")
        }
        val match = WECHAT_STATUS_REGEX.find(
            response.body.toString(StandardCharsets.UTF_8)
        ) ?: throw IOException("WeChat QR status response is malformed")
        val code = match.groupValues[1]
        val authorizationCode = match.groupValues[2]
        return when (code) {
            "408" -> QQMusicQrLoginCheckResult(
                if (scanned) QQMusicQrLoginStatus.SCANNED else QQMusicQrLoginStatus.WAITING
            )
            "404" -> {
                synchronized(state) {
                    state.scanned = true
                }
                QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.SCANNED)
            }
            "402" -> {
                cancel(state.id)
                QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.EXPIRED)
            }
            "403" -> {
                cancel(state.id)
                QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.REFUSED)
            }
            "405" -> {
                if (authorizationCode.isBlank()) {
                    throw IOException("WeChat authorization code is missing")
                }
                val credential = exchangeWechatLogin(state, authorizationCode)
                if (!completeState(state.id, state)) {
                    return QQMusicQrLoginCheckResult(QQMusicQrLoginStatus.EXPIRED)
                }
                QQMusicQrLoginCheckResult(
                    status = QQMusicQrLoginStatus.CONFIRMED,
                    cookies = credential.toAuthenticatedCookies()
                )
            }
            else -> throw IOException("WeChat QR login returned unsupported status $code")
        }
    }

    private fun exchangeWechatLogin(
        state: QrState.Wechat,
        authorizationCode: String
    ): QQCredential {
        val comm = state.device.toComm()
            .put("tmeLoginType", 1)
        val body = JSONObject()
            .put("comm", comm)
            .put(
                "req",
                JSONObject()
                    .put("module", "music.login.LoginServer")
                    .put("method", "Login")
                    .put(
                        "param",
                        JSONObject()
                            .put("code", authorizationCode)
                            .put("strAppid", WECHAT_APP_ID)
                    )
            )
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = execute(
            client = baseClient,
            request = Request.Builder()
                .url(QQ_MUSICU_URL)
                .header("User-Agent", QQ_LOGIN_USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .post(body)
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES
        )
        if (response.code !in 200..299) {
            throw IOException("WeChat Music credential exchange failed with HTTP ${response.code}")
        }
        return parseQqMusicLoginCredential(
            raw = response.body.toString(StandardCharsets.UTF_8),
            expectedLoginType = 1
        )
    }

    private fun bootstrapWechatDevice(): WechatDevice {
        val device = newWechatDevice()
        var now = clock()
        repeat(2) { attempt ->
            val envelope = buildQimeiEnvelope(device, now)
            val envelopeHeaders = Headers.Builder().apply {
                envelope.headers.forEach { (name, value) -> add(name, value) }
            }.build()
            val response = execute(
                client = baseClient,
                request = Request.Builder()
                    .url(QIMEI_URL)
                    .headers(envelopeHeaders)
                    .post(envelope.body.toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
                maxBytes = QQ_QR_MAX_TEXT_BYTES
            )
            if (response.code !in 200..299) {
                throw IOException("QIMEI bootstrap failed with HTTP ${response.code}")
            }
            val root = JSONObject(response.body.toString(StandardCharsets.UTF_8))
            val qimeiData = findQimeiData(root)
            val qimei = qimeiData?.jsonText("q16").orEmpty()
            val qimei36 = qimeiData?.jsonText("q36").orEmpty()
            if (qimei.isNotBlank() && qimei36.isNotBlank()) {
                device.qimei = qimei
                device.qimei36 = qimei36
                bootstrapWechatMusicSession(device)
                return device
            }
            val serverTime = response.date?.let(::parseHttpDate)
            if (attempt == 0 && serverTime != null &&
                kotlin.math.abs(serverTime - now) > 60_000L
            ) {
                now = serverTime
            } else {
                throw IOException("QIMEI bootstrap did not return device identifiers")
            }
        }
        throw IOException("QIMEI bootstrap did not return device identifiers")
    }

    private fun bootstrapWechatMusicSession(device: WechatDevice) {
        val body = JSONObject()
            .put("comm", device.toComm())
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.getSession.session")
                    .put("method", "GetSession")
                    .put(
                        "param",
                        JSONObject()
                            .put("uid", "")
                            .put("vkey", 0)
                            .put("caller", 0)
                    )
            )
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = execute(
            client = baseClient,
            request = Request.Builder()
                .url(QQ_MUSICU_URL)
                .header("User-Agent", QQ_LOGIN_USER_AGENT)
                .header("Cookie", "")
                .post(body)
                .build(),
            maxBytes = QQ_QR_MAX_TEXT_BYTES
        )
        if (response.code !in 200..299) {
            throw IOException("WeChat device session failed with HTTP ${response.code}")
        }
        val root = JSONObject(response.body.toString(StandardCharsets.UTF_8))
        val payload = root.optJSONObject("req_0")
            ?: root.optJSONObject("req")
            ?: throw IOException("WeChat device session response is missing req_0")
        val session = payload.optJSONObject("data")
            ?.optJSONObject("session")
            ?: throw IOException("WeChat device session response is missing data")
        device.uid = session.jsonText("uid").orEmpty()
        device.sid = session.jsonText("sid").orEmpty()
        if (device.uid.isBlank() || device.sid.isBlank()) {
            throw IOException("WeChat device session is incomplete")
        }
    }

    private fun buildQimeiEnvelope(device: WechatDevice, nowEpochMs: Long): QimeiEnvelope {
        val cryptKey = randomHex(8)
        val nonce = randomHex(8)
        val first = randomDigits(6)
        val second = randomDigits(9)
        val datedPositions = setOf(1, 2, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30, 33, 34, 37, 38)
        val datedValue = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(nowEpochMs)) + "-01$first.$second"
        val beacon = buildString {
            for (index in 1..40) {
                val value = when {
                    index == 3 -> "0000000000000000"
                    index == 4 -> cryptKey.replace('0', '1')
                    index in datedPositions -> datedValue
                    else -> randomDigits(4)
                }
                append("k$index:$value;")
            }
        }
        val reserved = JSONObject()
            .put("harmony", "0")
            .put("clone", "0")
            .put("containe", "")
            .put("oz", "UhYmelwouA+V2nPWbOvLTgN2/m8jwGB+yUB5v9tysQg=")
            .put("oo", "Xecjt+9S1+f8Pz2VLSxgpw==")
            .put("kelong", "0")
            .put(
                "uptimes",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(nowEpochMs - 3_600_000L))
            )
            .put("multiUser", "0")
            .put("bod", "Xiaomi")
            .put("dv", "sagit")
            .put("firstLevel", "")
            .put("manufact", "Xiaomi")
            .put("name", "MI 6")
            .put("host", "se.infra")
            .put("kernel", "Linux 5.4.0-54-generic (android-build@google.com)")
            .toString()
        val payload = JSONObject()
            .put("androidId", device.androidId)
            .put("platformId", 1)
            .put("appKey", QIMEI_APP_KEY)
            .put("appVersion", "14.9.0.8")
            .put("beaconIdSrc", beacon)
            .put("brand", "Xiaomi")
            .put("channelId", "10003505")
            .put("cid", "")
            .put("imei", device.imei)
            .put("imsi", "")
            .put("mac", "")
            .put("model", "MI 6")
            .put("networkType", "unknown")
            .put("oaid", "")
            .put("osVersion", "Android 10,level 29")
            .put("qimei", "")
            .put("qimei36", "")
            .put("sdkVersion", "1.2.13.6")
            .put("targetSdkVersion", 33)
            .put("audit", "")
            .put("userId", "{}")
            .put("packageId", "com.tencent.qqmusic")
            .put("deviceType", "Phone")
            .put("sdkName", "")
            .put("reserved", reserved)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val paddedPayload = payload.padPkcs7()
        val wrappedKey = encryptQimeiKey(cryptKey)
        val encryptedPayload = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(cryptKey.toByteArray(StandardCharsets.US_ASCII), "AES"),
                IvParameterSpec(cryptKey.toByteArray(StandardCharsets.US_ASCII))
            )
            doFinal(paddedPayload)
        }
        val key = Base64.getEncoder().encodeToString(wrappedKey)
        val params = Base64.getEncoder().encodeToString(encryptedPayload)
        val seconds = (nowEpochMs / 1_000L).toString()
        val millis = nowEpochMs.toString()
        val extra = JSONObject().put("appKey", QIMEI_APP_KEY).toString()
        val sign = protocolMd5(
            key,
            params,
            millis,
            nonce,
            "ZdJqM15EeO2zWc08",
            extra
        )
        val body = JSONObject()
            .put("app", 0)
            .put("os", 1)
            .put(
                "qimeiParams",
                JSONObject()
                    .put("key", key)
                    .put("params", params)
                    .put("time", seconds)
                    .put("nonce", nonce)
                    .put("sign", sign)
                    .put("extra", extra)
            )
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val headers = mapOf(
            "Method" to "GetQimei",
            "Service" to "trpc.tme_datasvr.qimeiproxy.QimeiProxy",
            "Appid" to "qimei_qq_android",
            "Sign" to protocolMd5(
                "qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH",
                seconds
            ),
            "Timestamp" to seconds,
            "Content-Type" to JSON_MEDIA_TYPE.toString(),
            "User-Agent" to QQ_LOGIN_USER_AGENT
        )
        return QimeiEnvelope(body = body, headers = headers)
    }

    private fun encryptQimeiKey(cryptKey: String): ByteArray {
        val modulus = java.math.BigInteger(QIMEI_RSA_MODULUS, 16)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(modulus, java.math.BigInteger.valueOf(65_537L))
        )
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, publicKey)
            doFinal(cryptKey.toByteArray(StandardCharsets.US_ASCII))
        }
    }

    private fun newWechatDevice(): WechatDevice {
        val first14 = randomDigits(14)
        var sum = 0
        first14.forEachIndexed { index, character ->
            var digit = character.digitToInt()
            if (index % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        val imei = first14 + ((10 - sum % 10) % 10)
        return WechatDevice(
            androidId = randomHex(8),
            openUdid = randomHex(16),
            imei = imei
        )
    }

    private fun newSession(
        channel: QQMusicQrLoginChannel,
        imageBytes: ByteArray
    ): QQMusicQrLoginSession {
        val now = clock()
        return QQMusicQrLoginSession(
            id = randomHex(24),
            channel = channel,
            imageBytes = imageBytes.copyOf(),
            expiresAtEpochMs = now + QQ_QR_SESSION_LIFETIME_MS
        )
    }

    private fun completeState(id: String, state: QrState): Boolean {
        synchronized(stateLock) {
            if (states[id] !== state) return false
            states.remove(id)
            return true
        }
    }

    private fun clearExpiredStates() {
        val now = clock()
        synchronized(stateLock) {
            states.entries.removeAll { (_, state) -> now >= state.expiresAt }
        }
    }

    private fun execute(
        client: OkHttpClient,
        request: Request,
        maxBytes: Long,
        jar: MemoryCookieJar? = null,
        includeCookies: Boolean = false
    ): QrHttpResponse {
        val requestWithCookies = if (jar != null && includeCookies &&
            request.header("Cookie") == null
        ) {
            jar.cookieHeader()
                ?.let { request.newBuilder().header("Cookie", it).build() }
                ?: request
        } else {
            request
        }
        client.newCall(requestWithCookies).execute().use { response ->
            jar?.storeSetCookieHeaders(response.headers("Set-Cookie"))
            val body = response.body.byteStream().use { it.readBytesLimited(maxBytes) }
            return QrHttpResponse(
                code = response.code,
                body = body,
                location = response.header("Location"),
                date = response.header("Date"),
                setCookies = response.headers("Set-Cookie")
            )
        }
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun randomDigits(count: Int): String = buildString(count) {
        repeat(count) { append(random.nextInt(10)) }
    }

    private sealed class QrState(
        open val id: String,
        open val expiresAt: Long,
        open val jar: MemoryCookieJar,
        open val channel: QQMusicQrLoginChannel
    ) {
        class Qq(
            override val id: String,
            override val expiresAt: Long,
            override val jar: MemoryCookieJar,
            val qrsig: String
        ) : QrState(id, expiresAt, jar, QQMusicQrLoginChannel.QQ)

        class Wechat(
            override val id: String,
            override val expiresAt: Long,
            override val jar: MemoryCookieJar,
            val uuid: String,
            val device: WechatDevice,
            var scanned: Boolean = false
        ) : QrState(id, expiresAt, jar, QQMusicQrLoginChannel.WECHAT)
    }

    private class MemoryCookieJar {
        private val cookies = linkedMapOf<String, String>()

        @Synchronized
        fun storeSetCookieHeaders(headers: List<String>) {
            headers.forEach { header ->
                val parts = header.split(';').map(String::trim)
                val nameValue = parts.firstOrNull().orEmpty()
                val separator = nameValue.indexOf('=')
                if (separator <= 0) return@forEach
                val name = nameValue.substring(0, separator).trim()
                val value = nameValue.substring(separator + 1).trim()
                val removed = value.isEmpty() || parts.any { part ->
                    part.equals("Max-Age=0", ignoreCase = true) ||
                        part.startsWith("Expires=Thu, 01 Jan 1970", ignoreCase = true)
                }
                if (removed) cookies.remove(name) else cookies[name] = value
            }
        }

        @Synchronized
        fun cookieValue(name: String): String? = cookies[name]

        @Synchronized
        fun cookieHeader(): String? = cookies.entries
            .joinToString("; ") { (name, value) -> "$name=$value" }
            .takeIf(String::isNotBlank)
    }

    private data class QrHttpResponse(
        val code: Int,
        val body: ByteArray,
        val location: String?,
        val date: String?,
        val setCookies: List<String>
    ) {
        fun setCookieNames(): String = setCookies.mapNotNull { header ->
            header.substringBefore(';')
                .substringBefore('=')
                .trim()
                .takeIf(String::isNotEmpty)
        }.distinct().sorted().joinToString(",")

        fun setCookieValue(name: String): String? {
            return setCookies.firstNotNullOfOrNull { header ->
                val firstPart = header.substringBefore(';').trim()
                val separator = firstPart.indexOf('=')
                if (separator <= 0 || firstPart.substring(0, separator) != name) {
                    null
                } else {
                    firstPart.substring(separator + 1).trim().takeIf(String::isNotBlank)
                }
            }
        }
    }

    private data class QimeiEnvelope(
        val body: ByteArray,
        val headers: Map<String, String>
    )
}

internal data class QQCredential(
    val musicId: String,
    val musicKey: String,
    val loginType: Int,
    val createdAt: Long,
    val expiresIn: Long,
    val encryptedUin: String,
    val openId: String,
    val refreshToken: String,
    val unionId: String,
    val refreshKey: String,
    val nickname: String,
    val avatarUrl: String
)

internal fun QQCredential.toAuthenticatedCookies(): Map<String, String> {
    // Keep the web login_type semantics separate from the API credential type.
    // QQ: login_type=1, tmeLoginType=2; WeChat: login_type=2, tmeLoginType=1.
    val webLoginType = if (loginType == 1) 2 else 1
    val cookies = linkedMapOf(
        "uin" to musicId,
        "qqmusic_uin" to musicId,
        "qqmusic_key" to musicKey,
        "qm_keyst" to musicKey,
        "musickey" to musicKey,
        "login_type" to webLoginType.toString(),
        "tmeLoginType" to loginType.toString()
    )
    if (loginType == 1) {
        cookies["wxuin"] = musicId
    }
    encryptedUin.takeIf(String::isNotBlank)?.let { cookies["euin"] = it }
    createdAt.takeIf { it > 0L }?.let {
        cookies["psrf_musickey_createtime"] = it.toString()
    }
    if (createdAt > 0L && expiresIn > 0L) {
        cookies["psrf_access_token_expiresAt"] = (createdAt + expiresIn).toString()
    }
    openId.takeIf(String::isNotBlank)?.let { cookies["psrf_qqopenid"] = it }
    unionId.takeIf(String::isNotBlank)?.let { cookies["psrf_qqunionid"] = it }
    if (loginType == 1) {
        openId.takeIf(String::isNotBlank)?.let { cookies["wxopenid"] = it }
        refreshToken.takeIf(String::isNotBlank)?.let { cookies["wxrefresh_token"] = it }
        unionId.takeIf(String::isNotBlank)?.let { cookies["wxunionid"] = it }
        refreshKey.takeIf(String::isNotBlank)?.let { cookies["wxrefresh_key"] = it }
    }
    return cookies
}

internal fun parsePtuiCallback(raw: String): List<String> {
    var text = raw.trim()
    if (text.endsWith(";")) text = text.dropLast(1).trimEnd()
    if (!text.startsWith("ptuiCB(") || !text.endsWith(")")) {
        throw IOException("QQ QR status is malformed")
    }
    text = text.substring("ptuiCB(".length, text.length - 1)
    val values = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) break
        if (text[index] != '\'') throw IOException("QQ QR status is malformed")
        index++
        val value = StringBuilder()
        var closed = false
        while (index < text.length) {
            val character = text[index++]
            when {
                character == '\'' -> {
                    closed = true
                    break
                }
                character != '\\' -> {
                    if (character == '\r' || character == '\n') {
                        throw IOException("QQ QR status is malformed")
                    }
                    value.append(character)
                }
                else -> {
                    if (index >= text.length) throw IOException("QQ QR status is malformed")
                    when (val escaped = text[index++]) {
                        '\'', '\\' -> value.append(escaped)
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'x' -> {
                            val hex = text.takeFrom(index, 2)
                            value.append(hex.toIntOrNull(16)?.toChar() ?: throw IOException("Invalid QQ QR escape"))
                            index += 2
                        }
                        'u' -> {
                            val hex = text.takeFrom(index, 4)
                            value.append(hex.toIntOrNull(16)?.toChar() ?: throw IOException("Invalid QQ QR escape"))
                            index += 4
                        }
                        else -> value.append(escaped)
                    }
                }
            }
        }
        if (!closed) throw IOException("QQ QR status is malformed")
        values += value.toString()
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) break
        if (text[index] != ',') throw IOException("QQ QR status is malformed")
        index++
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) throw IOException("QQ QR status is malformed")
    }
    if (values.size !in 6..7) throw IOException("QQ QR status is malformed")
    return values
}

internal fun parseQqMusicLoginCredential(
    raw: String,
    expectedLoginType: Int
): QQCredential {
    val root = runCatching { JSONObject(raw) }.getOrElse {
        throw IOException("QQ Music login response is malformed")
    }
    val gatewayCode = root.optInt("code", -1)
    val response = root.optJSONObject("req")
        ?: root.optJSONObject("req_0")
        ?: throw IOException("QQ Music login response is missing status")
    val businessCode = response.optInt("code", -1)
    if (gatewayCode != 0 || businessCode != 0) {
        throw IOException("QQ Music rejected login (gateway=$gatewayCode business=$businessCode)")
    }
    val data = response.optJSONObject("data")
        ?: throw IOException("QQ Music login response is missing data")
    val rawId = if (expectedLoginType == 1) {
        data.jsonText("str_musicid").orEmpty().ifBlank { data.jsonText("musicid").orEmpty() }
    } else {
        data.jsonText("musicid").orEmpty()
    }.removePrefix("o")
    val numericId = rawId.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: throw IOException("QQ Music login account id is invalid")
    val key = data.jsonText("musickey").orEmpty()
    if (key.isBlank()) throw IOException("QQ Music login credential is missing")
    val reportedType = data.flexibleLong("loginType").toInt()
    if (reportedType != 0 && reportedType != expectedLoginType) {
        throw IOException("QQ Music returned unsupported login type $reportedType")
    }
    return QQCredential(
        musicId = numericId.toString(),
        musicKey = key,
        loginType = expectedLoginType,
        createdAt = data.flexibleLong("musickeyCreateTime"),
        expiresIn = data.flexibleLong("keyExpiresIn"),
        encryptedUin = data.jsonText("encryptUin").orEmpty(),
        openId = data.jsonText("openid").orEmpty(),
        refreshToken = data.jsonText("refresh_token").orEmpty(),
        unionId = data.jsonText("unionid").orEmpty(),
        refreshKey = data.jsonText("refresh_key").orEmpty(),
        nickname = data.jsonText("nick").orEmpty(),
        avatarUrl = data.jsonText("logo").orEmpty()
    )
}

internal fun hash33(input: String, initial: Long): Long {
    var value = initial and 0xffff_ffffL
    input.forEach { character ->
        value = (value * 33L + character.code.toLong()) and 0xffff_ffffL
    }
    return value and 0x7fff_ffffL
}

private fun trustedQqLoginUrl(raw: String?, host: String, path: String): HttpUrl? {
    val url = raw?.toHttpUrlOrNull() ?: return null
    return url.takeIf {
        it.scheme == "https" &&
            it.host == host &&
            it.port == 443 &&
            it.encodedPath == path &&
            it.username.isEmpty() &&
            it.password.isEmpty() &&
            it.fragment == null
    }
}

private fun findQimeiData(root: JSONObject): JSONObject? {
    val candidates = ArrayDeque<JSONObject>()
    candidates.add(root)
    repeat(3) {
        val current = candidates.removeFirstOrNull() ?: return null
        if (current.has("q16") || current.has("q36")) return current
        when (val data = current.opt("data")) {
            is JSONObject -> candidates.add(data)
            is String -> runCatching { JSONObject(data) }.getOrNull()?.let(candidates::add)
            null -> Unit
            else -> Unit
        }
    }
    return candidates.firstOrNull { it.has("q16") || it.has("q36") }
}

private fun parseHttpDate(raw: String): Long? = runCatching {
    ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
        .toInstant()
        .toEpochMilli()
}.getOrNull()

private fun looksLikeImage(bytes: ByteArray): Boolean {
    return PNG_OR_JPEG_SIGNATURE.any { signature ->
        bytes.size >= signature.size &&
            signature.indices.all { index -> bytes[index] == signature[index] }
    }
}

private fun ByteArray.padPkcs7(): ByteArray {
    val padding = 16 - size % 16
    return this + ByteArray(padding) { padding.toByte() }
}

private fun protocolMd5(vararg parts: String): String {
    val digest = MessageDigest.getInstance("MD5")
        .digest(parts.joinToString("").toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.US, it) }
}

private fun JSONObject.jsonText(key: String): String? {
    val value = opt(key) ?: return null
    if (value == JSONObject.NULL) return null
    return when (value) {
        is String -> value.trim().takeIf(String::isNotBlank)
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> value.toString().trim().takeIf(String::isNotBlank)
    }
}

private fun JSONObject.flexibleLong(key: String): Long {
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: 0L
        null -> 0L
        else -> 0L
    }
}

private fun Map<String, String>.toCookieHeader(): String =
    entries.joinToString("; ") { (key, value) -> "$key=$value" }

private fun String.takeFrom(start: Int, count: Int): String {
    if (start < 0 || start + count > length) throw IOException("QQ QR escape is truncated")
    return substring(start, start + count)
}

private fun String.redacted(): String =
    if (length <= 8) "***" else "${take(4)}...${takeLast(4)}"

private class WechatDevice(
    val androidId: String,
    val openUdid: String,
    val imei: String,
    var qimei: String = "",
    var qimei36: String = "",
    var uid: String = "",
    var sid: String = ""
) {
    fun toComm(): JSONObject = JSONObject()
        .put("ct", 11)
        .put("cv", 14090008)
        .put("v", 14090008)
        .put("chid", "10003505")
        .put("tmeAppID", "qqmusic")
        .put("QIMEI", qimei)
        .put("QIMEI36", qimei36)
        .put("OpenUDID", openUdid)
        .put("udid", openUdid)
        .put("OpenUDID2", openUdid)
        .put("aid", androidId)
        .put("os_ver", "10")
        .put("phonetype", "MI 6")
        .put("devicelevel", "29")
        .put("newdevicelevel", "29")
        .put("rom", "xiaomi/iarim/sagit:10/eomam.200122.001/2001221:user/release-keys")
        .apply {
            if (uid.isNotBlank() && sid.isNotBlank()) {
                put("uid", uid)
                put("sid", sid)
            }
        }
}

private fun defaultQrHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .proxySelector(DynamicProxySelector)
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val QIMEI_APP_KEY = "0AND0HD6FE4HY80F"
private const val QIMEI_RSA_MODULUS =
    "c4231830a2eb5fc2827170641e79d80fec51bda9a22e4b4ab37d1f205a4ae44d" +
        "928cda25879f66a3429051663312a127faf8a246bdaaf63918417e90d7c95b5" +
        "908aa6a2d0f852e4a6770294a548ac1c2fe8f1f252fb826f4ac86ab9a00e7c" +
        "e47d002a56e7c4b51eb889acc60ca6adbc9f72e81f4d31b1dd746480526453" +
        "0ab1d"
