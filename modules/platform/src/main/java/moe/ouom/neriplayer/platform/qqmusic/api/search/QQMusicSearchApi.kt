package moe.ouom.neriplayer.platform.qqmusic.api.search

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/YGHFv/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.platform.qqmusic.api.search/QQMusicSearchApi
 * Created: 2025/8/17
 */

import android.annotation.SuppressLint
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.platform.search.api.NativeLyricSearchApi
import moe.ouom.neriplayer.platform.search.api.codec.decodeQQMusicLyricPayload
import moe.ouom.neriplayer.platform.search.api.codec.stripUntranslatedPlaceholderLines
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicDetailResponse
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicLyricContainer
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicSearchResponse
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicSongMetadata
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicTrackInfo
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private enum class SearchBackend { DESKTOP, LEGACY }

private data class SearchSessionKey(
    val query: String,
    val accountScope: String
)

class QQMusicSearchApi(
    private val client: OkHttpClient,
    private val debugLogging: Boolean = false,
    private val authProvider: () -> QQMusicAuthBundle = { QQMusicAuthBundle() }
) : NativeLyricSearchApi {

    companion object {
        private const val TAG = "QQMusicSearchApi"
        private const val DEBUG_JSON_PREVIEW_MAX_CHARS = 512
        private const val QQ_MUSIC_REFERER = "https://y.qq.com/"
        private const val QQ_MUSIC_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        private const val QQ_MUSIC_MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val QQ_MUSIC_LEGACY_SEARCH_URL =
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val QQ_MUSIC_SEARCH_PAGE_SIZE = 20
        private const val QQ_MUSIC_DESKTOP_SEARCH_MODULE = "music.search.SearchCgiService"
        private const val QQ_MUSIC_DESKTOP_SEARCH_METHOD = "DoSearchForQQMusicDesktop"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val pinnedSearchBackends = ConcurrentHashMap<SearchSessionKey, SearchBackend>()

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        return withContext(Dispatchers.IO) {
            val normalizedKeyword = keyword.trim()
            if (normalizedKeyword.isEmpty()) return@withContext emptyList()
            val normalizedPage = page.coerceAtLeast(1)
            val auth = authProvider()
            val sessionKey = SearchSessionKey(
                query = normalizedKeyword,
                accountScope = if (auth.hasLoginCookies()) {
                    "account:${auth.musicId}:${auth.loginType}"
                } else {
                    "anonymous"
                }
            )
            if (normalizedPage == 1) pinnedSearchBackends.remove(sessionKey)

            when (pinnedSearchBackends[sessionKey]) {
                SearchBackend.DESKTOP -> return@withContext searchDesktop(
                    keyword = normalizedKeyword,
                    page = normalizedPage,
                    auth = auth
                )
                SearchBackend.LEGACY -> return@withContext searchLegacy(
                    keyword = normalizedKeyword,
                    page = normalizedPage,
                    auth = auth
                )
                null -> Unit
            }

            try {
                val songs = searchDesktop(normalizedKeyword, normalizedPage, auth)
                pinnedSearchBackends[sessionKey] = SearchBackend.DESKTOP
                songs
            } catch (error: CancellationException) {
                throw error
            } catch (desktopError: Exception) {
                NPLogger.w(
                    TAG,
                    "QQ desktop search failed, falling back to legacy: " +
                        "page=$normalizedPage, ${desktopError.javaClass.simpleName}"
                )
                try {
                    val songs = searchLegacy(normalizedKeyword, normalizedPage, auth)
                    pinnedSearchBackends[sessionKey] = SearchBackend.LEGACY
                    songs
                } catch (error: CancellationException) {
                    throw error
                } catch (legacyError: Exception) {
                    legacyError.addSuppressed(desktopError)
                    throw legacyError
                }
            }
        }
    }

    private suspend fun searchDesktop(
        keyword: String,
        page: Int,
        auth: QQMusicAuthBundle
    ): List<SongSearchInfo> {
        val body = JSONObject()
            .put("comm", desktopSearchComm(auth))
            .put(
                "req_0",
                JSONObject()
                    .put("module", QQ_MUSIC_DESKTOP_SEARCH_MODULE)
                    .put("method", QQ_MUSIC_DESKTOP_SEARCH_METHOD)
                    .put(
                        "param",
                        JSONObject()
                            .put("query", keyword)
                            .put("search_type", 0)
                            .put("num_per_page", QQ_MUSIC_SEARCH_PAGE_SIZE)
                            .put("page_num", page)
                    )
            )
        val responseJson = executeRequest(
            Request.Builder()
                .url(QQ_MUSIC_MUSICU_URL)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .withQQMusicDesktopHeaders(auth)
                .build()
        ) as String
        val root = JSONObject(responseJson)
        val gatewayCode = root.optInt("code", -1)
        val response = root.optJSONObject("req_0")
            ?: throw IOException("QQ desktop search response is missing req_0")
        val code = response.optInt("code", -1)
        val subcode = response.optInt("subcode", 0)
        if (gatewayCode != 0 || code != 0 || subcode != 0) {
            throw IOException(
                "QQ desktop search rejected: gateway=$gatewayCode " +
                    "code=${if (code != 0) code else subcode}"
            )
        }
        val data = response.optJSONObject("data")
            ?: throw IOException("QQ desktop search response is missing data")
        val song = data.optJSONObject("body")?.optJSONObject("song")
            ?: throw IOException("QQ desktop search response is missing body.song")
        val entries = song.optJSONArray("list")
            ?: throw IOException("QQ desktop search response is missing song list")
        val meta = data.optJSONObject("meta")
            ?: throw IOException("QQ desktop search response is missing meta")

        val filter = meta.optInt("is_filter", 0)
        if (entries.length() == 0 && filter < 0) {
            throw IOException("QQ desktop search was filtered (is_filter=$filter)")
        }
        val total = meta.flexibleLong("sum", "total")
        if (page > 1 && entries.length() == 0 && total <= 0L) {
            throw IOException("QQ desktop search lost its pagination total")
        }
        return buildList {
            for (index in 0 until entries.length()) {
                entries.optJSONObject(index)?.let(::parseDesktopSong)?.let(::add)
            }
        }
    }

    private suspend fun searchLegacy(
        keyword: String,
        page: Int,
        auth: QQMusicAuthBundle
    ): List<SongSearchInfo> {
        val url = QQ_MUSIC_LEGACY_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("n", QQ_MUSIC_SEARCH_PAGE_SIZE.toString())
            .addQueryParameter("p", page.toString())
            .addQueryParameter("w", keyword)
            .addQueryParameter("t", "0")
            .addQueryParameter("cr", "1")
            .addQueryParameter("new_json", "1")
            .addQueryParameter("g_tk", "5381")
            .apply {
                auth.musicId?.let { addQueryParameter("loginUin", it) }
            }
            .build()

        val responseJson = executeRequest(
            Request.Builder().url(url).withQQMusicAuthHeaders().build()
        ) as String
        val searchResult = json.decodeFromString<QQMusicSearchResponse>(responseJson)
        if (searchResult.code != 0 || searchResult.subcode != 0) {
            throw IOException(
                "QQ legacy search rejected: code=${searchResult.code} " +
                    "subcode=${searchResult.subcode}"
            )
        }
        val songSection = searchResult.data?.song
            ?: throw IOException("QQ legacy search response is missing song section")
        val songs = songSection.list
            ?: throw IOException("QQ legacy search response is missing song list")
        if (page > 1 && songs.isEmpty() && (songSection.total ?: 0) <= 0) {
            throw IOException("QQ legacy search lost its pagination total")
        }
        return songs.map { song ->
            SongSearchInfo(
                id = song.songMid,
                platformId = song.songId?.toString(),
                songName = song.songName,
                singer = song.singer.joinToString("/") { it.name },
                duration = formatDuration(song.interval),
                source = MusicPlatform.QQ_MUSIC,
                albumName = song.albumName,
                coverUrl = song.albumMid?.let(::qqMusicAlbumCover),
                mediaId = song.file?.mediaMid?.trim()?.takeIf(String::isNotBlank)
                    ?: song.mediaMid?.trim()?.takeIf(String::isNotBlank)
            )
        }
    }

    private fun parseDesktopSong(value: JSONObject): SongSearchInfo? {
        val songMid = value.firstNonBlank("mid", "songmid", "songMid") ?: return null
        val songName = value.firstNonBlank("name", "songname", "songName", "title")
            ?: return null
        val album = value.optJSONObject("album")
        val albumMid = album?.firstNonBlank("mid", "albummid", "albumMid")
            ?: value.firstNonBlank("albummid", "album_mid", "albumMid")
        val albumName = album?.firstNonBlank("name", "title", "albumname", "albumName")
            ?: value.firstNonBlank("albumname", "album_name", "albumName")
        val artists = value.optJSONArray("singer")
            ?: value.optJSONArray("singers")
            ?: value.optJSONArray("singer_list")
        val file = value.optJSONObject("file")
        val mediaId = file?.firstNonBlank("media_mid", "mediaMid")
            ?: value.firstNonBlank("strMediaMid", "media_mid", "mediaMid")
        val durationSeconds = value.flexibleLong("interval", "duration")
            .coerceAtLeast(0L)
        return SongSearchInfo(
            id = songMid,
            platformId = value.flexibleLong("id", "songid", "songId")
                .takeIf { it > 0L }
                ?.toString(),
            songName = songName,
            singer = buildList {
                if (artists != null) {
                    for (index in 0 until artists.length()) {
                        artists.optJSONObject(index)
                            ?.firstNonBlank("name", "title", "singername", "singerName")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                }
            }.joinToString("/"),
            duration = formatDuration(durationSeconds),
            source = MusicPlatform.QQ_MUSIC,
            albumName = albumName,
            coverUrl = albumMid?.takeIf(String::isNotBlank)?.let(::qqMusicAlbumCover),
            mediaId = mediaId?.trim()?.takeIf(String::isNotBlank)
        )
    }

    override suspend fun getSongInfo(id: String): SongDetails = getNativeSongInfo(id)

    suspend fun getSongMetadata(id: String): QQMusicSongMetadata = withContext(Dispatchers.IO) {
        val song = fetchSongData(id)
        QQMusicSongMetadata(
            details = song.toSongDetails(lyric = null, translatedLyric = null),
            durationMs = song.interval.takeIf { it > 0L }?.times(1000L) ?: 0L
        )
    }

    override suspend fun getNativeSongInfo(id: String): SongDetails {
        return withContext(Dispatchers.IO) {
            val songData = fetchSongData(id)
            val (lyric, translatedLyric) = getNativeLyrics(songData.mid)
            songData.toSongDetails(
                lyric = lyric,
                translatedLyric = translatedLyric
            )
        }
    }

    private suspend fun fetchSongData(id: String): QQMusicTrackInfo {
        val detailRequestData = JSONObject()
            .put("comm", requestComm())
            .put(
                "songinfo", JSONObject()
                    .put("method", "get_song_detail_yqq")
                    .put("module", "music.pf_song_detail_svr")
                    .put(
                        "param", JSONObject()
                            .put("song_type", 0)
                            .put("song_mid", id)
                            .put("song_id", "")
                    )
            ).toString()

        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("data", detailRequestData)
            .build()

        val responseJson = executeRequest(
            Request.Builder().url(url).withQQMusicAuthHeaders().build()
        ) as String
        logDetailResponse(label = url.encodedPath, responseJson = responseJson)

        val songInfoJson = JSONObject(responseJson).optJSONObject("songinfo")?.toString()
            ?: throw IOException("响应中找不到 songinfo 字段")

        return json.decodeFromString<QQMusicDetailResponse>(songInfoJson).data?.trackInfo
            ?: throw IOException("找不到ID为 $id 的歌曲详情")
    }

    private fun QQMusicTrackInfo.toSongDetails(
        lyric: String?,
        translatedLyric: String?
    ): SongDetails {
        return SongDetails(
            id = mid,
            songName = name,
            singer = singer.joinToString("/") { it.name },
            album = album.name,
            coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000${album.mid}.jpg",
            lyric = lyric,
            translatedLyric = translatedLyric
        )
    }

    private fun logDetailResponse(label: String, responseJson: String) {
        val preview = responseJson
            .replace(Regex("\\s+"), " ")
            .take(DEBUG_JSON_PREVIEW_MAX_CHARS)
        if (debugLogging) {
            NPLogger.d(TAG, "获取歌曲详情响应: label=$label, length=${responseJson.length}, preview=$preview")
            return
        }
        NPLogger.d(TAG, "获取歌曲详情响应: labelHash=${label.hashCode()}, length=${responseJson.length}")
    }

    suspend fun getNativeLyrics(songMid: String): Pair<String?, String?> {
        return try {
            val lyricRequestData = JSONObject()
                .put("comm", requestComm())
                .put(
                    "req", JSONObject()
                        .put("method", "GetPlayLyricInfo")
                        .put("module", "music.musichallSong.PlayLyricInfo")
                        .put(
                            "param", JSONObject()
                                .put("songMID", songMid)
                                // 不显式点名要翻译, 接口只回一个空的 trans
                                .put("trans", 1)
                                .put("qrc", 0)
                                .put("crypt", 0)
                        )
                ).toString()

            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("data", lyricRequestData)
                .build()

            val request = Request.Builder().url(url).withQQMusicAuthHeaders().build()

            val responseJson = executeRequest(request) as String
            val envelope = json.decodeFromString<QQMusicLyricContainer>(responseJson).req
            if (envelope == null || envelope.code != 0) {
                if (envelope != null) {
                    NPLogger.w(
                        TAG,
                        "QQ lyric request rejected: songMid=$songMid code=${envelope.code}"
                    )
                }
                Pair(null, null)
            } else {
                val lyricResponse = envelope.data

                val lyric = decodeQQMusicLyricPayload(lyricResponse?.lyric)

                val translatedLyric = stripUntranslatedPlaceholderLines(
                    decodeQQMusicLyricPayload(lyricResponse?.trans)
                )

                Pair(lyric, translatedLyric)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e(TAG, "获取QQ音乐歌词失败", error)
            Pair(null, null)
        }
    }

    @Throws(IOException::class)
    private suspend fun executeRequest(request: Request, asBytes: Boolean = false): Any {
        return client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (asBytes) body.bytes() else body.string()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }

    private fun requestComm(): JSONObject {
        val auth = authProvider()
        return JSONObject()
            .put("uin", auth.musicId ?: "0")
            .put("ct", 24)
            .put("cv", 0)
            .apply {
                if (auth.hasLoginCookies()) {
                    put("qq", auth.musicId)
                    put("authst", auth.musicKey)
                    put("tmeLoginType", auth.loginType)
                }
            }
    }

    private fun desktopSearchComm(auth: QQMusicAuthBundle): JSONObject {
        return if (auth.hasLoginCookies()) {
            JSONObject()
                .put("uin", auth.musicId)
                .put("qq", auth.musicId)
                .put("authst", auth.musicKey)
                .put("tmeLoginType", auth.loginType)
                .put("ct", 11)
                .put("cv", 0)
                .put("format", "json")
        } else {
            JSONObject()
                .put("uin", "0")
                .put("ct", 11)
                .put("cv", 0)
                .put("format", "json")
        }
    }

    private fun Request.Builder.withQQMusicAuthHeaders(): Request.Builder {
        header("Referer", QQ_MUSIC_REFERER)
        header("User-Agent", QQ_MUSIC_USER_AGENT)
        val cookies = authProvider().cookies
        if (cookies.isNotEmpty()) header("Cookie", cookies.toCookieHeader())
        return this
    }

    private fun Request.Builder.withQQMusicDesktopHeaders(
        auth: QQMusicAuthBundle
    ): Request.Builder {
        header("Referer", QQ_MUSIC_REFERER)
        header("User-Agent", QQ_MUSIC_USER_AGENT)
        if (auth.hasLoginCookies()) {
            header("Cookie", auth.cookies.toCookieHeader())
        } else {
            // Desktop search otherwise inherits stale imported account cookies.
            header("Cookie", "tmeLoginType=-1")
        }
        return this
    }
}

private fun JSONObject.firstNonBlank(vararg keys: String): String? {
    keys.forEach { key ->
        val value = optString(key, "").trim()
        if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) return value
    }
    return null
}

private fun Map<String, String>.toCookieHeader(): String =
    entries.joinToString("; ") { (key, value) -> "$key=$value" }

private fun JSONObject.flexibleLong(vararg keys: String): Long {
    keys.forEach { key ->
        when (val value = opt(key)) {
            is Number -> return value.toLong()
            is String -> value.trim().toLongOrNull()?.let { return it }
        }
    }
    return 0L
}

private fun qqMusicAlbumCover(albumMid: String): String =
    "https://y.qq.com/music/photo_new/T002R800x800M000$albumMid.jpg"
