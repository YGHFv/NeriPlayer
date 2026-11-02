package moe.ouom.neriplayer.platform.qqmusic.api.profile

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAccountProfile
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val QQ_MUSIC_WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

class QQMusicAccountProfileClient(
    private val client: OkHttpClient
) {
    suspend fun getAccountProfile(bundle: QQMusicAuthBundle): QQMusicAccountProfile? {
        val musicId = bundle.musicId ?: return null
        if (!bundle.hasLoginCookies()) return null
        try {
            loadMusicuProfile(bundle, musicId)?.let { return it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Legacy homepage remains the compatibility fallback for older sessions.
        }
        return loadLegacyProfile(bundle, musicId)
    }

    private suspend fun loadMusicuProfile(
        bundle: QQMusicAuthBundle,
        musicId: String
    ): QQMusicAccountProfile? {
        val body = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("uin", musicId)
                    .put("qq", musicId)
                    .put("authst", bundle.musicKey)
                    .put("tmeLoginType", bundle.loginType)
                    .put("ct", 24)
                    .put("cv", 0)
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.UserInfo.userInfoServer")
                    .put("method", "GetLoginUserInfo")
                    .put("param", JSONObject())
            )
            .toString()
        val request = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", QQ_MUSIC_WEB_USER_AGENT)
            .header("Cookie", bundle.cookies.toCookieHeader())
            .build()
        val raw = execute(request)
        return parseQQMusicAccountProfile(raw = raw, fallbackMusicId = musicId)
    }

    private suspend fun loadLegacyProfile(
        bundle: QQMusicAuthBundle,
        musicId: String
    ): QQMusicAccountProfile? {
        val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("cid", "205360838")
            .addQueryParameter("userid", musicId)
            .addQueryParameter("reqfrom", "1")
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", QQ_MUSIC_WEB_USER_AGENT)
            .header("Cookie", bundle.cookies.toCookieHeader())
            .build()
        val raw = execute(request)
        return parseQQMusicAccountProfile(raw = raw, fallbackMusicId = musicId)
    }

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).awaitResponse { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.string()
            }
        }
}

internal fun parseQQMusicAccountProfile(
    raw: String,
    fallbackMusicId: String
): QQMusicAccountProfile? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val response = root.optJSONObject("req_0")
    if (
        root.optInt("code", 0) == 1000 ||
        response?.optInt("code", 0) == 1000
    ) {
        return null
    }
    val data = root.optJSONObject("data")
        ?: response?.optJSONObject("data")
        ?: root
    val info = data.optJSONObject("info")
    val creator = data.optJSONObject("creator")
        ?: data.optJSONObject("profile")
        ?: data.optJSONObject("user")
    val musicId = firstNonBlank(
        data.optString("hostuin"),
        data.optString("uin"),
        info?.optString("uin"),
        info?.optString("musicid"),
        creator?.optString("uin"),
        fallbackMusicId
    ) ?: return null
    val nickname = firstNonBlank(
        data.optString("hostname"),
        data.optString("nickname"),
        data.optString("name"),
        info?.optString("nick"),
        info?.optString("nickname"),
        info?.optString("name"),
        creator?.optString("nick"),
        creator?.optString("nickname"),
        creator?.optString("name")
    ) ?: return null
    val avatar = firstNonBlank(
        data.optString("headurl"),
        data.optString("headpic"),
        data.optString("avatarUrl"),
        data.optString("avatar"),
        info?.optString("headurl"),
        info?.optString("logo"),
        info?.optString("avatarUrl"),
        info?.optString("avatar"),
        creator?.optString("headpic"),
        creator?.optString("avatarUrl"),
        creator?.optString("avatar")
    )?.normalizeAvatarUrl()
    return QQMusicAccountProfile(
        musicId = musicId,
        nickname = nickname,
        avatarUrl = avatar
    )
}

internal fun Map<String, String>.toCookieHeader(): String =
    entries.joinToString("; ") { (key, value) -> "$key=$value" }

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { value -> !value.isNullOrBlank() && value != "null" }?.trim()

private fun String.normalizeAvatarUrl(): String? = trim().let { url ->
    when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
        url.startsWith("https://") -> url
        else -> null
    }
}
