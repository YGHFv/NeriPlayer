package moe.ouom.neriplayer.platform.qqmusic.api.client

import java.io.IOException
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicAudioStream
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicLibraryPlaylist
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicLibraryPlaylistsResult
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicPlaylistKind
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicRecommendationFeed
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicRecommendationPlaylist
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicRecommendationShelf
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicSong
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class QQMusicAuthenticationRequiredException :
    IOException("QQ Music login is required")

class QQMusicProtocolException(
    val module: String,
    val method: String,
    val gatewayCode: Int,
    val code: Int
) : IOException("QQ Music $module/$method rejected: gateway=$gatewayCode code=$code")

class QQMusicClient(
    private val client: OkHttpClient,
    private val authProvider: () -> QQMusicAuthBundle = { QQMusicAuthBundle() },
    private val musicuUrl: String = "https://u.y.qq.com/cgi-bin/musicu.fcg",
    private val cgiBaseUrl: String = "https://c.y.qq.com",
    private val probeStreamCandidates: Boolean = true
) {
    private companion object {
        const val TAG = "QQMusicClient"
        const val QQ_MUSIC_REFERER = "https://y.qq.com/"
        const val QQ_MUSIC_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100
        const val MAX_RECOMMENDATION_PAGES = 3
        const val MAX_RECOMMENDATION_SONG_SHELVES = 3
        const val MAX_RECOMMENDATION_TRACKS = 60
        const val MAX_RECOMMENDATION_PLAYLIST_PAGES = 2
        const val RECOMMENDATION_PLAYLIST_PAGE_SIZE = 25
        const val MAX_RECOMMENDATION_PLAYLISTS = 60
        const val RECOMMENDATION_SONG_SHELF_ID = 207L
        const val PREFERRED_STREAM_DOMAIN = "https://sjy6.stream.qqmusic.qq.com/"
        const val FALLBACK_STREAM_DOMAIN = "https://dl.stream.qqmusic.qq.com/"
        private val BACKUP_STREAM_DOMAINS = listOf(
            FALLBACK_STREAM_DOMAIN,
            "https://isure.stream.qqmusic.qq.com/",
            "https://ws.stream.qqmusic.qq.com/"
        )
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun getLibraryPlaylists(): QQMusicLibraryPlaylistsResult = withContext(Dispatchers.IO) {
        val auth = requireAuthenticatedAuth()
        val owned = loadOwnedPlaylists(auth)
        val collectedResult = try {
            Result.success(loadCollectedPlaylists(auth))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "Failed to load collected QQ Music playlists", error)
            Result.failure(error)
        }
        val collected = collectedResult.getOrDefault(emptyList())
        val playlists = (owned + collected)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<QQMusicLibraryPlaylist> {
                    it.kind == QQMusicPlaylistKind.LIKED
                }.thenByDescending { it.kind == QQMusicPlaylistKind.CREATED }
            )
        QQMusicLibraryPlaylistsResult(
            playlists = playlists,
            warning = collectedResult.exceptionOrNull()?.message
        )
    }

    suspend fun getPlaylistDetail(
        playlistId: Long,
        dirId: Long = 0L
    ): QQMusicPlaylistDetail = withContext(Dispatchers.IO) {
        if (playlistId <= 0L && dirId != 201L) {
            throw IOException("Invalid QQ Music playlist id")
        }
        val auth = authProvider()
        val failures = mutableListOf<Throwable>()
        if (auth.hasLoginCookies()) {
            try {
                return@withContext loadAuthenticatedPlaylistDetail(
                    playlistId = playlistId,
                    dirId = dirId,
                    auth = auth
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += error
            }
        }
        try {
            return@withContext loadPublicPlaylistDetail(playlistId, dirId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failures += error
        }
        if (auth.hasLoginCookies()) {
            try {
                return@withContext loadAiPlaylistDetail(playlistId, dirId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += error
            }
        }
        throw IOException(
            "Unable to load QQ Music playlist $playlistId",
            failures.lastOrNull()
        )
    }

    suspend fun getSongDetail(
        songMid: String,
        auth: QQMusicAuthBundle = authProvider()
    ): QQMusicSong = withContext(Dispatchers.IO) {
        val data = executeMusicu(
            module = "music.pf_song_detail_svr",
            method = "get_song_detail_yqq",
            param = JSONObject()
                .put("song_type", 0)
                .put("song_mid", songMid)
                .put("song_id", ""),
            auth = auth
        )
        val track = data.optJSONObject("track_info")
            ?: throw IOException("QQ Music song detail response is missing track_info")
        parseQQMusicSong(track)
            ?: throw IOException("QQ Music song detail response has no valid song")
    }

    suspend fun getHomeRecommendations(): QQMusicRecommendationFeed =
        withContext(Dispatchers.IO) {
            val auth = requireAuthenticatedAuth()
            val commOverrides = recommendationCommOverrides()
            val shelves = mutableListOf<JSONObject>()
            var loadMark = 0
            for (page in 1..MAX_RECOMMENDATION_PAGES) {
                val data = executeMusicu(
                    module = "music.recommend.RecommendFeed",
                    method = "get_recommend_feed",
                    param = JSONObject()
                        .put("direction", if (page == 1) 0 else 1)
                        .put("page", page)
                        .put("s_num", shelves.size)
                        .put("v_cache", JSONArray())
                        .put("v_uniq", JSONArray()),
                    auth = auth,
                    commOverrides = commOverrides
                )
                val pageShelves = data.optJSONArray("v_shelf")
                if (pageShelves == null) {
                    if (page == 1) return@withContext QQMusicRecommendationFeed(
                        shelves = emptyList(),
                        playlists = emptyList()
                    )
                    break
                }
                for (index in 0 until pageShelves.length()) {
                    pageShelves.optJSONObject(index)?.let(shelves::add)
                }
                loadMark = data.optInt("load_mark", 0)
                if (pageShelves.length() == 0 || loadMark != 0) break
            }

            val songShelfValues = shelves
                .filter { it.flexibleLong("id") == RECOMMENDATION_SONG_SHELF_ID }
                .mapNotNull { shelf ->
                    val songIds = parseRecommendationSongIds(shelf)
                    if (songIds.isEmpty()) null else shelf to songIds
                }
                .take(MAX_RECOMMENDATION_SONG_SHELVES)
            val allSongIds = songShelfValues
                .flatMap { (_, ids) -> ids }
                .distinct()
                .take(MAX_RECOMMENDATION_TRACKS)
            val tracksById = if (allSongIds.isEmpty()) {
                emptyMap()
            } else {
                loadRecommendationTracks(allSongIds, auth, commOverrides)
            }
            val songShelves = songShelfValues.mapNotNull { (shelf, ids) ->
                val songs = ids.mapNotNull(tracksById::get)
                if (songs.isEmpty()) {
                    null
                } else {
                    val titleTemplate = shelf.firstNonBlank("title_template").orEmpty()
                    val titleContent = shelf.firstNonBlank("title_content").orEmpty()
                    val title = titleTemplate
                        .replace("{String}", titleContent)
                        .replace("\u2062", "")
                        .trim()
                        .ifBlank { titleContent.trim() }
                        .ifBlank { "QQ Music recommendations" }
                    QQMusicRecommendationShelf(title = title, songs = songs)
                }
            }
            val squarePlaylists = try {
                loadRecommendationSquarePlaylists(auth, commOverrides)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(
                    TAG,
                    "QQ Music recommendation playlist feed failed",
                    error
                )
                emptyList()
            }
            QQMusicRecommendationFeed(
                shelves = songShelves,
                playlists = mergeRecommendationPlaylists(
                    parseRecommendationPlaylists(shelves),
                    squarePlaylists
                )
            )
        }

    suspend fun getPlaybackStream(
        songMid: String,
        mediaMid: String?,
        preferredQuality: String,
        probeCandidates: Boolean = probeStreamCandidates,
        allowPreview: Boolean = true
    ): QQMusicAudioStream = withContext(Dispatchers.IO) {
        if (songMid.isBlank()) throw IOException("QQ Music song MID is required")
        val auth = authProvider()
        var songDetail: QQMusicSong? = null
        val qualities = playbackQualityFallbacks(preferredQuality)
        val resolvedMediaMid = mediaMid
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: runCatching {
                getSongDetail(songMid, auth)
                    .also { songDetail = it }
                    .mediaMid
            }.getOrNull()
                ?.takeIf(String::isNotBlank)
            ?: songMid
        if (songDetail == null && qualities.any { it.mediaRole != QualityMediaRole.BASE }) {
            songDetail = runCatching { getSongDetail(songMid, auth) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    NPLogger.w(
                        TAG,
                        "QQ Music advanced media lookup failed: mid=$songMid",
                        error
                    )
                }
                .getOrNull()
        }
        val guid = Random.nextInt(1_000_000_000, Int.MAX_VALUE).toString()
        val authenticated = auth.hasLoginCookies()
        var lastVkeyError: Exception? = null

        for (quality in qualities) {
            val qualityMediaMid = qualityMediaMid(
                quality = quality,
                songDetail = songDetail,
                fallbackMediaMid = resolvedMediaMid
            ) ?: continue
            for (filename in playbackFilenames(quality, songMid, qualityMediaMid)) {
                val vkeyData = try {
                    loadVkey(
                        songMid = songMid,
                        filename = filename,
                        quality = quality,
                        guid = guid,
                        auth = auth,
                        anonymous = false
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (authenticated) {
                        lastVkeyError = error
                        NPLogger.w(
                            TAG,
                            "QQ Music vkey request failed: mid=$songMid quality=${quality.key}",
                            error
                        )
                        continue
                    }
                    throw error
                }
                resolveVkeyStream(
                    data = vkeyData,
                    songMid = songMid,
                    quality = quality,
                    guid = guid,
                    auth = auth,
                    probeCandidates = probeCandidates
                )?.let { return@withContext it }
            }
        }

        if (authenticated) {
            val anonymous = loadVkey(
                songMid = songMid,
                filename = playbackFilenames(
                    quality = StandardQuality,
                    songMid = songMid,
                    mediaMid = resolvedMediaMid
                ).single(),
                quality = StandardQuality,
                guid = guid,
                auth = QQMusicAuthBundle(),
                anonymous = true
            )
            resolveVkeyStream(
                data = anonymous,
                songMid = songMid,
                quality = StandardQuality,
                guid = guid,
                auth = auth,
                probeCandidates = probeCandidates
            )?.let { return@withContext it }
        }

        if (!allowPreview) {
            lastVkeyError?.let { error ->
                throw IOException("QQ Music vkey request failed for $songMid", error)
            }
            throw IOException("QQ Music returned no full playable URL for $songMid")
        }

        // QQ Music can still provide an official short preview for tracks that
        // otherwise return result=104003 (no account entitlement/vip).
        val previewMediaMid = runCatching {
            songDetail ?: getSongDetail(songMid, auth).also { songDetail = it }
        }.getOrNull()
            ?.previewMediaMid
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: songMid
        val previewAttempts = buildList {
            if (authenticated) add(auth to false)
            add(QQMusicAuthBundle() to true)
        }
        for ((previewAuth, anonymous) in previewAttempts) {
            val previewData = try {
                loadVkey(
                    songMid = songMid,
                    filename = playbackFilenames(
                        quality = PreviewQuality,
                        songMid = songMid,
                        mediaMid = previewMediaMid
                    ).single(),
                    quality = PreviewQuality,
                    guid = guid,
                    auth = previewAuth,
                    anonymous = anonymous
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastVkeyError = error
                NPLogger.w(
                    TAG,
                    "QQ Music preview vkey request failed: mid=$songMid",
                    error
                )
                continue
            }
            resolveVkeyStream(
                data = previewData,
                songMid = songMid,
                quality = PreviewQuality,
                guid = guid,
                auth = if (authenticated) auth else previewAuth,
                // A preview purl is already an official QQ Music URL. Some
                // CDN mirrors reject HEAD/range probes even though ExoPlayer
                // can stream them, so let the player validate the first URL.
                probeCandidates = false
            )?.let { return@withContext it }
        }
        lastVkeyError?.let { error ->
            throw IOException("QQ Music vkey request failed for $songMid", error)
        }
        throw IOException("QQ Music returned no playable URL for $songMid")
    }

    private suspend fun loadOwnedPlaylists(auth: QQMusicAuthBundle): List<QQMusicLibraryPlaylist> {
        val data = executeMusicu(
            module = "music.musicasset.PlaylistBaseRead",
            method = "GetPlaylistByUin",
            param = JSONObject().put("uin", auth.musicId),
            auth = auth
        )
        val values = data.optJSONArray("v_playlist")
            ?: throw IOException("QQ Music owned playlist list is missing")
        return parseLibraryPlaylists(
            values = values,
            defaultKind = QQMusicPlaylistKind.CREATED
        )
    }

    private suspend fun loadCollectedPlaylists(
        auth: QQMusicAuthBundle
    ): List<QQMusicLibraryPlaylist> {
        try {
            return loadCollectedPlaylistsFromProfile(auth)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val encryptedUin = auth.encryptedUin
                ?: throw IOException("QQ Music encrypted UIN is unavailable", error)
            return loadCollectedPlaylistsFromMusicu(auth, encryptedUin)
        }
    }

    private suspend fun loadCollectedPlaylistsFromProfile(
        auth: QQMusicAuthBundle
    ): List<QQMusicLibraryPlaylist> {
        val result = mutableListOf<QQMusicLibraryPlaylist>()
        var offset = 0
        var total = -1L
        repeat(MAX_PAGES) {
            val root = executeCgi(
                path = "/fav/fcgi-bin/fcg_get_profile_order_asset.fcg",
                query = mapOf(
                    "ct" to "20",
                    "cid" to "205360956",
                    "userid" to auth.musicId.orEmpty(),
                    "reqtype" to "3",
                    "sin" to offset.toString(),
                    "ein" to (offset + PAGE_SIZE - 1).toString()
                ),
                auth = auth
            )
            val data = root.optJSONObject("data")
                ?: throw IOException("QQ Music collected playlist response is missing data")
            total = data.flexibleLong("totaldiss", "total")
            val values = data.optJSONArray("cdlist")
            if (values == null) {
                if (total <= 0L && offset == 0) return emptyList()
                throw IOException("QQ Music collected playlist list is missing")
            }
            val page = parseLibraryPlaylists(values, QQMusicPlaylistKind.FAVORITE)
            val before = result.size
            result += page.filterNot { candidate ->
                result.any { existing -> existing.id == candidate.id }
            }
            if (page.isEmpty() || result.size == before) return result
            if (total in 0..result.size.toLong()) return result
            offset += values.length()
            if (values.length() < PAGE_SIZE && total <= 0L) return result
        }
        throw IOException("QQ Music collected playlist list exceeded the page limit")
    }

    private suspend fun loadCollectedPlaylistsFromMusicu(
        auth: QQMusicAuthBundle,
        encryptedUin: String
    ): List<QQMusicLibraryPlaylist> {
        val result = mutableListOf<QQMusicLibraryPlaylist>()
        var offset = 0
        repeat(MAX_PAGES) {
            val data = executeMusicu(
                module = "music.musicasset.PlaylistFavRead",
                method = "CgiGetPlaylistFavInfo",
                param = JSONObject()
                    .put("uin", encryptedUin)
                    .put("offset", offset)
                    .put("size", PAGE_SIZE),
                auth = auth
            )
            val values = data.optJSONArray("v_list")
            if (values == null) {
                if (offset == 0 && !data.hasMore("hasmore")) return emptyList()
                throw IOException("QQ Music favorite playlist list is missing")
            }
            val page = parseLibraryPlaylists(values, QQMusicPlaylistKind.FAVORITE)
            val before = result.size
            result += page.filterNot { candidate ->
                result.any { existing -> existing.id == candidate.id }
            }
            if (values.length() == 0 || result.size == before) return result
            if (!data.hasMore("hasmore")) return result
            offset += values.length()
        }
        throw IOException("QQ Music favorite playlist list exceeded the page limit")
    }

    private suspend fun loadAuthenticatedPlaylistDetail(
        playlistId: Long,
        dirId: Long,
        auth: QQMusicAuthBundle
    ): QQMusicPlaylistDetail {
        val liked = dirId == 201L
        return loadPagedPlaylistDetail(playlistId = playlistId) { offset ->
            executeMusicu(
                module = "music.srfDissInfo.DissInfo",
                method = "CgiGetDiss",
                param = JSONObject()
                    .put("disstid", if (liked) 0 else playlistId)
                    .put("dirid", if (liked) 201 else 0)
                    .put("tag", true)
                    .put("song_begin", offset)
                    .put("song_num", PAGE_SIZE)
                    .put("userinfo", true)
                    .put("orderlist", true)
                    .put("enc_host_uin", auth.encryptedUin.orEmpty()),
                auth = auth
            )
        }
    }

    private suspend fun loadAiPlaylistDetail(
        playlistId: Long,
        dirId: Long
    ): QQMusicPlaylistDetail {
        val liked = dirId == 201L
        return loadPagedPlaylistDetail(playlistId = playlistId) { offset ->
            executeMusicu(
                module = "music.srfDissInfo.aiDissInfo",
                method = "uniform_get_Dissinfo",
                param = JSONObject()
                    .put("disstid", if (liked) 0 else playlistId)
                    .put("dirid", if (liked) 201 else 0)
                    .put("userinfo", 1)
                    .put("tag", 1)
                    .put("orderlist", 1)
                    .put("song_begin", offset)
                    .put("song_num", PAGE_SIZE)
                    .put("onlysonglist", 0)
                    .put("enc_host_uin", "")
            )
        }
    }

    private suspend fun loadPublicPlaylistDetail(
        playlistId: Long,
        dirId: Long
    ): QQMusicPlaylistDetail {
        if (dirId == 201L) throw IOException("Liked playlist requires QQ Music login")
        val root = executeCgi(
            path = "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",
            query = mapOf(
                "type" to "1",
                "json" to "1",
                "utf8" to "1",
                "onlysong" to "0",
                "new_format" to "1",
                "disstid" to playlistId.toString()
            ),
            auth = authProvider()
        )
        val cdlist = root.optJSONArray("cdlist")
            ?: throw IOException("QQ Music public playlist response is missing cdlist")
        val info = cdlist.optJSONObject(0)
            ?: throw IOException("QQ Music public playlist is unavailable")
        val songsArray = info.optJSONArray("songlist")
        if (songsArray == null && info.flexibleInt("total_song_num", "songnum") > 0) {
            throw IOException("QQ Music public playlist song list is missing")
        }
        val songs = parseQQMusicSongs(songsArray ?: JSONArray())
        val trackCount = info.flexibleInt("total_song_num", "songnum")
            .takeIf { it > 0 }
            ?: songs.size
        return QQMusicPlaylistDetail(
            id = playlistId,
            name = info.firstNonBlank("dissname", "title", "name").orEmpty(),
            coverUrl = normalizeQQMusicImageUrl(
                info.firstNonBlank("logo", "picurl", "picUrl", "diss_cover")
            ).orEmpty(),
            playCount = info.flexibleLong("visitnum", "listennum", "listen_num"),
            trackCount = trackCount,
            tracks = songs
        )
    }

    private suspend fun loadPagedPlaylistDetail(
        playlistId: Long,
        loadPage: suspend (offset: Int) -> JSONObject
    ): QQMusicPlaylistDetail {
        val tracks = linkedMapOf<String, QQMusicSong>()
        var name = ""
        var coverUrl = ""
        var playCount = 0L
        var total = 0
        var offset = 0
        var completed = false
        for (pageIndex in 0 until MAX_PAGES) {
            val data = loadPage(offset)
            ensureDataStatus(data, "playlist-detail", "page")
            val info = data.optJSONObject("dirinfo")
            if (offset == 0 && info != null) {
                name = info.firstNonBlank("title", "dissname", "name").orEmpty()
                coverUrl = normalizeQQMusicImageUrl(
                    info.firstNonBlank("picurl", "picUrl", "logo", "diss_cover")
                ).orEmpty()
                playCount = info.flexibleLong("visitnum", "listennum")
                total = info.flexibleInt("total_song_num", "songnum", "songNum")
                    .takeIf { it > 0 }
                    ?: data.flexibleInt("total_song_num", "songnum")
            }
            if (offset == 0 && total <= 0) {
                total = data.flexibleInt("total_song_num", "songnum")
            }
            val songArray = data.optJSONArray("songlist")
            if (songArray == null) {
                if (offset == 0 && total <= 0) {
                    return QQMusicPlaylistDetail(
                        id = playlistId,
                        name = name,
                        coverUrl = coverUrl,
                        playCount = playCount,
                        trackCount = 0,
                        tracks = emptyList()
                    )
                }
                throw IOException("QQ Music playlist song list is missing")
            }
            val page = parseQQMusicSongs(songArray)
            val before = tracks.size
            page.forEach { song -> tracks.putIfAbsent(song.mid, song) }
            if (page.isEmpty() || tracks.size == before) {
                if (offset == 0 && total > 0) {
                    throw IOException("QQ Music playlist returned no valid songs")
                }
                completed = true
                break
            }
            val hasMore = data.hasMore("hasmore")
            if (!hasMore && (total <= 0 || tracks.size >= total)) {
                completed = true
                break
            }
            offset += songArray.length()
            if (total > 0 && tracks.size >= total) {
                completed = true
                break
            }
            if (!hasMore && songArray.length() < PAGE_SIZE) {
                completed = true
                break
            }
        }
        if (!completed) {
            throw IOException("QQ Music playlist exceeded the page limit")
        }
        val values = tracks.values.toList()
        return QQMusicPlaylistDetail(
            id = playlistId,
            name = name,
            coverUrl = coverUrl,
            playCount = playCount,
            trackCount = total.takeIf { it > 0 } ?: values.size,
            tracks = values
        )
    }

    private suspend fun loadVkey(
        songMid: String,
        filename: String,
        quality: PlaybackQuality,
        guid: String,
        auth: QQMusicAuthBundle,
        anonymous: Boolean
    ): JSONObject {
        val authenticated = !anonymous && auth.hasLoginCookies()
        val param = JSONObject()
            .put("filename", JSONArray().put(filename))
            .put("guid", guid)
            .put("songmid", JSONArray().put(songMid))
            .put("songtype", JSONArray().put(0))
            .put("uin", if (authenticated) auth.musicId else "0")
            .put("loginflag", 1)
            .put("platform", "20")
        if (authenticated) {
            param.put("ctx", 0)
        }
        val data = executeMusicu(
            module = if (authenticated) "music.vkey.GetVkey" else "vkey.GetVkeyServer",
            method = if (authenticated) "UrlGetVkey" else "CgiGetVkey",
            param = param,
            auth = auth,
            anonymousComm = anonymous
        )
        val urls = data.optJSONArray("midurlinfo")
        val purlCount = (0 until (urls?.length() ?: 0))
            .count { index -> urls?.optJSONObject(index)?.optString("purl").orEmpty().isNotBlank() }
        val resultCodes = (0 until (urls?.length() ?: 0))
            .mapNotNull { index -> urls?.optJSONObject(index)?.optInt("result") }
        NPLogger.d(
            TAG,
            "vkey response mid=$songMid quality=${quality.key} authenticated=$authenticated " +
                "entries=${urls?.length() ?: 0} purls=$purlCount " +
                "sip=${data.optJSONArray("sip")?.length() ?: 0} " +
                "results=${resultCodes.joinToString("/")}"
        )
        return data
    }

    private suspend fun resolveVkeyStream(
        data: JSONObject,
        songMid: String,
        quality: PlaybackQuality,
        guid: String,
        auth: QQMusicAuthBundle,
        probeCandidates: Boolean = probeStreamCandidates
    ): QQMusicAudioStream? {
        val entries = data.optJSONArray("midurlinfo") ?: return null
        val purl = findPurl(entries, songMid) ?: return null
        val isPreviewClip = quality.key == PreviewQuality.key ||
            purl.contains("RS02", ignoreCase = true)
        val resolvedQuality = if (isPreviewClip) PreviewQuality else quality
        val domains = linkedSetOf<String>()
        data.optJSONArray("sip")?.forEachString { raw ->
            normalizeQQMusicStreamDomain(raw)?.let(domains::add)
        }
        domains += PREFERRED_STREAM_DOMAIN
        runCatching {
            executeMusicu(
                module = "music.audioCdnDispatch.cdnDispatch",
                method = "GetCdnDispatch",
                param = JSONObject()
                    .put("guid", guid)
                    .put("uid", "0")
                    .put("use_new_domain", 1)
                    .put("use_ipv6", 1),
                auth = auth
            )
        }.getOrNull()?.optJSONArray("sip")?.forEachString { raw ->
            normalizeQQMusicStreamDomain(raw)?.let(domains::add)
        }
        domains += BACKUP_STREAM_DOMAINS

        val candidates = domains.mapNotNull { domain ->
            resolveQQMusicStreamUrl(domain, purl)
        }.distinct()
        if (candidates.isEmpty()) return null
        if (!probeCandidates) {
            return QQMusicAudioStream(
                url = candidates.first(),
                candidateUrls = candidates.drop(1),
                mimeType = resolvedQuality.mimeType,
                fileExtensionHint = resolvedQuality.extension,
                qualityKey = resolvedQuality.key,
                isPreviewClip = isPreviewClip
            )
        }
        for ((index, candidate) in candidates.withIndex()) {
            val probe = probeStream(candidate, auth)
            if (probe != null) {
                return QQMusicAudioStream(
                    url = candidate,
                    candidateUrls = candidates.drop(index + 1) + candidates.take(index),
                    mimeType = resolvedQuality.mimeType,
                    fileExtensionHint = resolvedQuality.extension,
                    qualityKey = resolvedQuality.key,
                    contentLength = probe.contentLength,
                    isPreviewClip = isPreviewClip
                )
            }
        }
        return null
    }

    private suspend fun probeStream(
        url: String,
        auth: QQMusicAuthBundle
    ): StreamProbe? = withContext(Dispatchers.IO) {
        val host = url.toHttpUrlOrNull()?.host ?: "invalid"
        try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .withQQMusicHeaders(auth)
                    .build()
            ).awaitResponse { response ->
                if (response.code != 200 && response.code != 206) {
                    NPLogger.w(
                        TAG,
                        "QQ Music stream probe rejected: host=$host status=${response.code}"
                    )
                    return@awaitResponse null
                }
                val contentLength = response.header("Content-Range")
                    ?.substringAfterLast('/', "")
                    ?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                NPLogger.d(
                    TAG,
                    "QQ Music stream probe accepted: host=$host status=${response.code} " +
                        "contentLength=${contentLength ?: 0L}"
                )
                StreamProbe(contentLength?.takeIf { it > 0L })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "QQ Music stream probe failed: host=$host error=${error.javaClass.simpleName}"
            )
            null
        }
    }

    private suspend fun executeMusicu(
        module: String,
        method: String,
        param: JSONObject,
        auth: QQMusicAuthBundle = authProvider(),
        anonymousComm: Boolean = false,
        commOverrides: JSONObject? = null
    ): JSONObject {
        val comm = requestComm(auth, anonymous = anonymousComm)
        commOverrides?.let { overrides ->
            val keys = overrides.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                comm.put(key, overrides.get(key))
            }
        }
        val body = JSONObject()
            .put("comm", comm)
            .put(
                "req_0",
                JSONObject()
                    .put("module", module)
                    .put("method", method)
                    .put("param", param)
            )
        val request = Request.Builder()
            .url(musicuUrl)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .withQQMusicHeaders(auth)
            .build()
        val raw = executeRequest(request)
        val root = JSONObject(raw)
        val gatewayCode = root.optInt("code", -1)
        val response = root.optJSONObject("req_0")
            ?: throw IOException("QQ Music response is missing req_0")
        val code = response.optInt("code", -1)
        val subcode = response.optInt("subcode", 0)
        if (gatewayCode != 0 || code != 0 || subcode != 0) {
            throw QQMusicProtocolException(
                module = module,
                method = method,
                gatewayCode = gatewayCode,
                code = if (code != 0) code else subcode
            )
        }
        return response.optJSONObject("data")
            ?: throw IOException("QQ Music response is missing req_0.data")
    }

    private suspend fun executeCgi(
        path: String,
        query: Map<String, String>,
        auth: QQMusicAuthBundle
    ): JSONObject {
        val url = cgiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments(path.trimStart('/'))
            .apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
                addQueryParameter("format", "json")
                addQueryParameter("outCharset", "utf-8")
            }
            .build()
        val raw = executeRequest(
            Request.Builder()
                .url(url)
                .withQQMusicHeaders(auth)
                .build()
        )
        val root = JSONObject(raw.trim().let(::stripJsonpWrapper))
        val code = root.optInt("code", 0)
        val subcode = root.optInt("subcode", 0)
        if (code != 0 || subcode != 0) {
            throw QQMusicProtocolException("cgi", path, 0, if (code != 0) code else subcode)
        }
        return root
    }

    private suspend fun executeRequest(request: Request): String {
        return client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }

    private fun requireAuthenticatedAuth(): QQMusicAuthBundle {
        val auth = authProvider()
        if (!auth.hasLoginCookies()) throw QQMusicAuthenticationRequiredException()
        return auth
    }

    private fun requestComm(
        auth: QQMusicAuthBundle,
        anonymous: Boolean = false
    ): JSONObject {
        if (anonymous || !auth.hasLoginCookies()) {
            return JSONObject()
                .put("uin", "0")
                .put("format", "json")
                .put("ct", 24)
                .put("cv", 0)
        }
        return JSONObject()
            .put("uin", auth.musicId)
            .put("qq", auth.musicId)
            .put("authst", auth.musicKey)
            .put("tmeLoginType", auth.loginType)
            .put("ct", 24)
            .put("cv", 0)
    }

    private fun Request.Builder.withQQMusicHeaders(auth: QQMusicAuthBundle): Request.Builder {
        header("Referer", QQ_MUSIC_REFERER)
        header("User-Agent", QQ_MUSIC_USER_AGENT)
        if (auth.cookies.isNotEmpty()) {
            header(
                "Cookie",
                auth.cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
            )
        }
        return this
    }

    private fun parseLibraryPlaylists(
        values: JSONArray,
        defaultKind: QQMusicPlaylistKind
    ): List<QQMusicLibraryPlaylist> {
        val result = mutableListOf<QQMusicLibraryPlaylist>()
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: continue
            val id = value.flexibleLong("tid", "dissid", "id", "dirid", "dirId")
            val name = value.firstNonBlank(
                "dirName",
                "title",
                "dissname",
                "diss_name",
                "name"
            ) ?: continue
            if (id <= 0L || name.isBlank()) continue
            val dirId = value.flexibleLong("dirId", "dirid")
            val kind = when {
                dirId == 201L -> QQMusicPlaylistKind.LIKED
                defaultKind == QQMusicPlaylistKind.CREATED -> QQMusicPlaylistKind.CREATED
                else -> QQMusicPlaylistKind.FAVORITE
            }
            result += QQMusicLibraryPlaylist(
                id = id,
                dirId = dirId,
                name = name,
                coverUrl = normalizeQQMusicImageUrl(
                    value.firstNonBlank(
                        "bigpicUrl",
                        "picUrl",
                        "picurl",
                        "diss_cover",
                        "logo",
                        "albumPicUrl",
                        "coverUrl"
                    )
                ).orEmpty(),
                trackCount = value.flexibleInt("songNum", "songnum", "song_cnt")
                    .coerceAtLeast(0),
                playCount = value.flexibleLong("play_cnt", "playCount", "listen_num")
                    .coerceAtLeast(0L),
                kind = kind
            )
        }
        return result
    }

    private fun parseQQMusicSongs(values: JSONArray): List<QQMusicSong> {
        val result = mutableListOf<QQMusicSong>()
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: continue
            parseQQMusicSong(value)?.let(result::add)
        }
        return result
    }

    private fun parseRecommendationSongIds(shelf: JSONObject): List<Long> {
        val cardSongIds = linkedSetOf<Long>()
        val cardOtherIds = linkedSetOf<Long>()
        val niches = shelf.optJSONArray("v_niche")
        for (nicheIndex in 0 until (niches?.length() ?: 0)) {
            val cards = niches?.optJSONObject(nicheIndex)?.optJSONArray("v_card")
            for (cardIndex in 0 until (cards?.length() ?: 0)) {
                val card = cards?.optJSONObject(cardIndex) ?: continue
                val id = card.optString("id", "").trim().toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: continue
                if (card.optInt("type", 0) == 200) {
                    cardSongIds += id
                } else {
                    cardOtherIds += id
                }
            }
        }
        val result = linkedSetOf<Long>()
        fun addCandidate(id: Long) {
            if (id > 0L && (id !in cardOtherIds || id in cardSongIds)) {
                result += id
            }
        }
        val songList = shelf.optJSONObject("miscellany")?.opt("songList")
        when (songList) {
            is JSONArray -> {
                for (index in 0 until songList.length()) {
                    songList.optString(index, "")
                        .trim()
                        .toLongOrNull()
                        ?.let(::addCandidate)
                }
            }
            is String -> {
                songList.split(',')
                    .mapNotNull { it.trim().takeIf(String::isNotBlank)?.toLongOrNull() }
                    .forEach(::addCandidate)
            }
        }
        if (result.isEmpty()) cardSongIds.forEach(::addCandidate)
        return result.take(MAX_RECOMMENDATION_TRACKS)
    }

    private suspend fun loadRecommendationTracks(
        songIds: List<Long>,
        auth: QQMusicAuthBundle,
        commOverrides: JSONObject
    ): Map<Long, QQMusicSong> {
        val data = executeMusicu(
            module = "music.trackInfo.UniformRuleCtrl",
            method = "CgiGetTrackInfo",
            param = JSONObject()
                .put("ids", JSONArray(songIds))
                .put("types", JSONArray(List(songIds.size) { 0 })),
            auth = auth,
            commOverrides = commOverrides
        )
        val tracks = data.optJSONArray("tracks")
            ?: throw IOException("QQ Music recommendation tracks are missing")
        return parseQQMusicSongs(tracks)
            .associateBy { it.id }
    }

    private fun parseRecommendationPlaylists(
        shelves: List<JSONObject>
    ): List<QQMusicRecommendationPlaylist> {
        val result = linkedMapOf<Long, QQMusicRecommendationPlaylist>()
        shelves.forEach { shelf ->
            val niches = shelf.optJSONArray("v_niche")
            for (nicheIndex in 0 until (niches?.length() ?: 0)) {
                val cards = niches?.optJSONObject(nicheIndex)?.optJSONArray("v_card")
                for (cardIndex in 0 until (cards?.length() ?: 0)) {
                    val card = cards?.optJSONObject(cardIndex) ?: continue
                    if (
                        card.optInt("type", 0) != 500 ||
                        card.optInt("subtype", 0) != 0 ||
                        card.optInt("jumptype", 0) != 10014
                    ) {
                        continue
                    }
                    val id = card.optString("id", "").trim().toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?: continue
                    val name = card.firstNonBlank("title", "name", "dissname")
                        ?: continue
                    result.putIfAbsent(
                        id,
                        QQMusicRecommendationPlaylist(
                            id = id,
                            name = name,
                            coverUrl = normalizeQQMusicImageUrl(
                                card.firstNonBlank("cover", "coverUrl", "pic", "picurl", "logo")
                            ).orEmpty(),
                            playCount = card.flexibleLong("cnt", "count", "play_count")
                                .coerceAtLeast(0L),
                            trackCount = card.flexibleInt("song_cnt", "songnum", "track_count")
                                .coerceAtLeast(0)
                        )
                    )
                }
            }
        }
        return result.values.take(MAX_RECOMMENDATION_PLAYLISTS)
    }

    private suspend fun loadRecommendationSquarePlaylists(
        auth: QQMusicAuthBundle,
        commOverrides: JSONObject
    ): List<QQMusicRecommendationPlaylist> {
        val result = linkedMapOf<Long, QQMusicRecommendationPlaylist>()
        for (page in 0 until MAX_RECOMMENDATION_PLAYLIST_PAGES) {
            val data = executeMusicu(
                module = "music.playlist.PlaylistSquare",
                method = "GetRecommendFeed",
                param = JSONObject()
                    .put("From", page * RECOMMENDATION_PLAYLIST_PAGE_SIZE)
                    .put("Size", RECOMMENDATION_PLAYLIST_PAGE_SIZE),
                auth = auth,
                commOverrides = commOverrides
            )
            val playlists = parseRecommendationSquarePlaylists(data)
            playlists.forEach { playlist ->
                result.putIfAbsent(playlist.id, playlist)
            }
            if (
                playlists.isEmpty() ||
                !data.optBoolean("HasMore", false) ||
                result.size >= MAX_RECOMMENDATION_PLAYLISTS
            ) {
                break
            }
        }
        return result.values.take(MAX_RECOMMENDATION_PLAYLISTS)
    }

    private fun parseRecommendationSquarePlaylists(
        data: JSONObject
    ): List<QQMusicRecommendationPlaylist> {
        val entries = data.optJSONArray("List") ?: return emptyList()
        val result = mutableListOf<QQMusicRecommendationPlaylist>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val basic = entry.optJSONObject("Playlist")
                ?.optJSONObject("basic")
                ?: entry.optJSONObject("basic")
                ?: continue
            val id = basic.flexibleLong("tid", "id", "dissid")
                .takeIf { it > 0L }
                ?: continue
            val name = basic.firstNonBlank("title", "name", "dissname") ?: continue
            val cover = basic.optJSONObject("cover")
                ?.firstNonBlank("default_url", "url", "picurl")
                ?: basic.firstNonBlank("picurl", "cover", "logo")
            result += QQMusicRecommendationPlaylist(
                id = id,
                name = name,
                coverUrl = normalizeQQMusicImageUrl(cover).orEmpty(),
                playCount = basic.flexibleLong("play_cnt", "listennum", "playCnt")
                    .coerceAtLeast(0L),
                trackCount = basic.flexibleInt("song_cnt", "songnum", "songNum")
                    .coerceAtLeast(0)
            )
        }
        return result
    }

    private fun mergeRecommendationPlaylists(
        primary: List<QQMusicRecommendationPlaylist>,
        additional: List<QQMusicRecommendationPlaylist>
    ): List<QQMusicRecommendationPlaylist> {
        val merged = linkedMapOf<Long, QQMusicRecommendationPlaylist>()
        (primary + additional).forEach { playlist ->
            merged.putIfAbsent(playlist.id, playlist)
        }
        return merged.values.take(MAX_RECOMMENDATION_PLAYLISTS)
    }

    private fun recommendationCommOverrides(): JSONObject = JSONObject()
        .put("format", "json")
        .put("ct", 20)
        .put("cv", 2276)
        .put("platform", "wk_v17")
        .put("inCharset", "utf-8")
        .put("outCharset", "utf-8")
        .put("notice", 0)
        .put("needNewCode", 1)
        .put("g_tk", 0)
        .put("g_tk_new_20200303", 0)

    private fun parseQQMusicSong(raw: JSONObject): QQMusicSong? {
        val value = raw.optJSONObject("musicData")
            ?: raw.optJSONObject("songInfo")
            ?: raw.optJSONObject("track_info")
            ?: raw
        val mid = value.firstNonBlank("mid", "songmid", "songMid") ?: return null
        val name = value.firstNonBlank("title", "name", "songname", "songName") ?: return null
        val album = value.optJSONObject("album") ?: JSONObject()
        val albumMid = album.firstNonBlank("mid", "albummid", "albumMid")
            ?: value.firstNonBlank("albummid", "album_mid")
            .orEmpty()
        val albumName = album.firstNonBlank("name", "title", "albumname", "albumName")
            ?: value.firstNonBlank("albumname", "album_name")
            .orEmpty()
        val file = value.optJSONObject("file") ?: JSONObject()
        val artist = parseQQMusicArtists(value.optJSONArray("singer")
            ?: value.optJSONArray("singers"))
        val mediaMid = file.firstNonBlank("media_mid")
            ?: value.firstNonBlank("strMediaMid", "media_mid", "mediaMid")
            .orEmpty()
        val versions = value.optJSONArray("vs")
        val previewMediaMid = versions?.let { values ->
            (0 until values.length()).firstNotNullOfOrNull { index ->
                values.optString(index).trim().takeIf(String::isNotBlank)
            }
        }.orEmpty()
        val masterMediaMid = versions?.qualityMediaMidAt(3).orEmpty()
        val hiresMediaMid = versions?.qualityMediaMidAt(4).orEmpty()
        val atmos71MediaMid = versions?.qualityMediaMidAt(24).orEmpty()
        val dtsMediaMid = versions?.qualityMediaMidAt(30).orEmpty()
        val sizeNew = file.optJSONArray("size_new")
        fun sizeNewAt(index: Int): Long = sizeNew?.optLong(index, 0L)
            ?.takeIf { it > 0L }
            ?: 0L
        val numericId = value.flexibleLong("id", "songid", "songId")
            .takeIf { it > 0L }
            ?: stableQQMusicNumericId(mid)
        return QQMusicSong(
            id = numericId,
            mid = mid,
            mediaMid = mediaMid.ifBlank { mid },
            name = name,
            artist = artist,
            album = albumName,
            albumId = album.flexibleLong("id", "albumid", "albumId")
                .takeIf { it > 0L }
                ?: value.flexibleLong("albumid", "album_id"),
            albumMid = albumMid,
            durationMs = value.flexibleLong("interval", "duration")
                .coerceAtLeast(0L)
                .let { seconds -> if (seconds in 1..10_000L) seconds * 1_000L else seconds },
            coverUrl = normalizeQQMusicImageUrl(
                albumMid.takeIf(String::isNotBlank)?.let { "https://y.qq.com/music/photo_new/T002R800x800M000$it.jpg" }
                    ?: value.firstNonBlank("albumPicUrl", "albumPic")
            ),
            size128 = file.flexibleLong("size_128mp3")
                .takeIf { it > 0L }
                ?: value.flexibleLong("size128"),
            size192Ogg = file.flexibleLong("size_192ogg")
                .takeIf { it > 0L }
                ?: 0L,
            size320 = file.flexibleLong("size_320mp3")
                .takeIf { it > 0L }
                ?: value.flexibleLong("size320"),
            sizeFlac = file.flexibleLong("size_flac")
                .takeIf { it > 0L }
                ?: value.flexibleLong("sizeflac"),
            sizeOgg320 = sizeNewAt(3),
            sizeOgg640 = sizeNewAt(5),
            sizeDolby = file.flexibleLong("size_dolby")
                .takeIf { it > 0L }
                ?: 0L,
            previewMediaMid = previewMediaMid,
            masterMediaMid = masterMediaMid,
            hiresMediaMid = hiresMediaMid,
            atmos71MediaMid = atmos71MediaMid,
            dtsMediaMid = dtsMediaMid,
            sizeMaster = sizeNewAt(0),
            sizeHires = sizeNewAt(1),
            sizeAtmos51 = sizeNewAt(2),
            sizeAtmos71 = sizeNewAt(6),
            sizeDtsx = sizeNewAt(9)
        )
    }

    private fun parseQQMusicArtists(values: JSONArray?): String {
        if (values == null) return ""
        return buildList {
            for (index in 0 until values.length()) {
                values.optJSONObject(index)
                    ?.firstNonBlank("name", "singername", "singer_name", "singerName")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.joinToString("/")
    }

    private fun findPurl(entries: JSONArray, songMid: String): String? {
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val entryMid = entry.optString("songmid")
            if (entryMid.isNotBlank() && entryMid != songMid) continue
            val purl = entry.optString("purl")
            if (purl.isNotBlank()) return purl
        }
        return null
    }

    private fun ensureDataStatus(data: JSONObject, module: String, method: String) {
        val code = data.optInt("code", 0)
        val subcode = data.optInt("subcode", 0)
        if (code != 0 || subcode != 0) {
            throw QQMusicProtocolException(module, method, 0, if (code != 0) code else subcode)
        }
    }

    private fun playbackQualityFallbacks(preferredQuality: String): List<PlaybackQuality> {
        val preferred = preferredQuality.trim().lowercase(Locale.US)
        return when (preferred) {
            "master", "jymaster" ->
                listOf(
                    MasterQuality,
                    HiresQuality,
                    Atmos71Quality,
                    Atmos51Quality,
                    DolbyQuality,
                    DtsxQuality,
                    FlacQuality,
                    Ogg640Quality,
                    Ogg320Quality,
                    Mp3320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "hires", "jyeffect", "flac24bit", "hi-res" ->
                listOf(
                    HiresQuality,
                    FlacQuality,
                    Ogg640Quality,
                    Ogg320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "atmos71", "atmos_71" ->
                listOf(
                    Atmos71Quality,
                    Atmos51Quality,
                    FlacQuality,
                    Ogg320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "atmos51", "atmos_51", "atmos", "sky" ->
                listOf(
                    Atmos51Quality,
                    Atmos71Quality,
                    FlacQuality,
                    Ogg320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "dolby" ->
                listOf(
                    DolbyQuality,
                    Atmos71Quality,
                    Atmos51Quality,
                    FlacQuality,
                    Ogg320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "dtsx", "dts", "dts_x" ->
                listOf(
                    DtsxQuality,
                    Atmos71Quality,
                    Atmos51Quality,
                    FlacQuality,
                    Ogg320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "lossless", "flac" ->
                listOf(
                    FlacQuality,
                    Ogg640Quality,
                    Ogg320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "exhigh", "320", "320k" ->
                listOf(
                    Ogg320Quality,
                    Mp3320Quality,
                    Ogg192Quality,
                    StandardQuality
                )
            "higher", "192", "192k" ->
                listOf(Ogg192Quality, StandardQuality)
            else -> listOf(StandardQuality)
        }
    }

    private fun qualityMediaMid(
        quality: PlaybackQuality,
        songDetail: QQMusicSong?,
        fallbackMediaMid: String
    ): String? {
        val dedicatedMediaMid = when (quality.mediaRole) {
            QualityMediaRole.BASE -> null
            QualityMediaRole.MASTER -> songDetail?.masterMediaMid
            QualityMediaRole.HIRES -> songDetail?.hiresMediaMid
            QualityMediaRole.ATMOS_71 -> songDetail?.atmos71MediaMid
            QualityMediaRole.DTS_X -> songDetail?.dtsMediaMid
        }?.trim()?.takeIf(String::isNotBlank)
        return when (quality.mediaRole) {
            QualityMediaRole.BASE -> fallbackMediaMid
            else -> dedicatedMediaMid
        }
    }

    private fun playbackFilenames(
        quality: PlaybackQuality,
        songMid: String,
        mediaMid: String
    ): List<String> {
        val resolvedMediaMid = mediaMid.trim().ifBlank { songMid }
        return when (quality.key) {
            PreviewQuality.key -> listOf(
                "${quality.prefix}$resolvedMediaMid.${quality.extension}"
            )
            else -> if (quality.filenameLayout == QualityFilenameLayout.MEDIA_ONLY) {
                buildList {
                    // Advanced formats use their dedicated media MID. Keep the
                    // song-MID prefix as a compatibility fallback for older data.
                    add("${quality.prefix}$resolvedMediaMid.${quality.extension}")
                    if (resolvedMediaMid != songMid) {
                        add("${quality.prefix}$songMid$resolvedMediaMid.${quality.extension}")
                    }
                }.distinct()
            } else {
                listOf("${quality.prefix}$songMid$resolvedMediaMid.${quality.extension}")
            }
        }
    }

    private fun normalizeQQMusicStreamDomain(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val normalized = if (value.startsWith("http://")) {
            value.replaceFirst("http://", "https://")
        } else {
            value
        }
        val url = runCatching { normalized.toHttpUrl() }.getOrNull() ?: return null
        val host = url.host.lowercase(Locale.US)
        if (host.startsWith("ws.")) return null
        val trusted = host == "qqmusic.qq.com" ||
            host.endsWith(".qqmusic.qq.com") ||
            host.endsWith(".music.tc.qq.com")
        if (!trusted) return null
        return url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/') + "/"
    }

    private fun resolveQQMusicStreamUrl(domain: String, purl: String): String? {
        val base = runCatching { domain.toHttpUrl() }.getOrNull() ?: return null
        return runCatching { base.resolve(purl)?.toString() }.getOrNull()
    }

    private fun JSONObject.hasMore(key: String): Boolean {
        val value = opt(key) ?: return false
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() == 1
            is String -> value.equals("true", ignoreCase = true) ||
                value.equals("1") ||
                value.toIntOrNull() == 1
            else -> false
        }
    }

    private fun stableQQMusicNumericId(mid: String): Long {
        var hash = 0xcbf29ce484222325UL
        mid.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3UL
        }
        return (hash and 0x7fff_ffff_ffff_ffffUL).toLong().coerceAtLeast(1L)
    }
}

private data class PlaybackQuality(
    val prefix: String,
    val extension: String,
    val mimeType: String,
    val key: String,
    val mediaRole: QualityMediaRole = QualityMediaRole.BASE,
    val filenameLayout: QualityFilenameLayout = QualityFilenameLayout.SONG_AND_MEDIA
)

private data class StreamProbe(val contentLength: Long?)

private enum class QualityMediaRole {
    BASE,
    MASTER,
    HIRES,
    ATMOS_71,
    DTS_X
}

private enum class QualityFilenameLayout {
    SONG_AND_MEDIA,
    MEDIA_ONLY
}

private val StandardQuality = PlaybackQuality(
    prefix = "M500",
    extension = "mp3",
    mimeType = "audio/mpeg",
    key = "standard"
)
private val Ogg192Quality = PlaybackQuality(
    prefix = "O600",
    extension = "ogg",
    mimeType = "audio/ogg",
    key = "higher"
)
private val Ogg320Quality = PlaybackQuality(
    prefix = "O800",
    extension = "ogg",
    mimeType = "audio/ogg",
    key = "exhigh"
)
private val Mp3320Quality = PlaybackQuality(
    prefix = "M800",
    extension = "mp3",
    mimeType = "audio/mpeg",
    key = "exhigh"
)
private val MasterQuality = PlaybackQuality(
    prefix = "AI00",
    extension = "flac",
    mimeType = "audio/flac",
    key = "master",
    mediaRole = QualityMediaRole.MASTER,
    filenameLayout = QualityFilenameLayout.MEDIA_ONLY
)
private val HiresQuality = PlaybackQuality(
    prefix = "Q000",
    extension = "flac",
    mimeType = "audio/flac",
    key = "hires",
    mediaRole = QualityMediaRole.HIRES,
    filenameLayout = QualityFilenameLayout.MEDIA_ONLY
)
private val Atmos51Quality = PlaybackQuality(
    prefix = "Q001",
    extension = "flac",
    mimeType = "audio/flac",
    key = "atmos51",
    filenameLayout = QualityFilenameLayout.MEDIA_ONLY
)
private val Atmos71Quality = PlaybackQuality(
    prefix = "Q003",
    extension = "ogg",
    mimeType = "audio/ogg",
    key = "atmos71",
    mediaRole = QualityMediaRole.ATMOS_71,
    filenameLayout = QualityFilenameLayout.MEDIA_ONLY
)
private val DolbyQuality = PlaybackQuality(
    prefix = "D004",
    extension = "mp4",
    mimeType = "audio/mp4",
    key = "dolby",
    filenameLayout = QualityFilenameLayout.MEDIA_ONLY
)
private val DtsxQuality = PlaybackQuality(
    prefix = "DT03",
    extension = "mp4",
    mimeType = "audio/mp4",
    key = "dtsx",
    mediaRole = QualityMediaRole.DTS_X,
    filenameLayout = QualityFilenameLayout.MEDIA_ONLY
)
private val FlacQuality = PlaybackQuality(
    prefix = "F000",
    extension = "flac",
    mimeType = "audio/flac",
    key = "lossless"
)
private val Ogg640Quality = PlaybackQuality(
    prefix = "O801",
    extension = "ogg",
    mimeType = "audio/ogg",
    key = "lossless"
)
private val PreviewQuality = PlaybackQuality(
    prefix = "RS02",
    extension = "mp3",
    mimeType = "audio/mpeg",
    key = "preview"
)

private fun JSONArray.qualityMediaMidAt(index: Int): String =
    optString(index, "").trim().takeIf(String::isNotBlank).orEmpty()

private fun JSONObject.flexibleLong(vararg keys: String): Long {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is Number -> return value.toLong()
            is String -> value.trim().toLongOrNull()?.let { return it }
        }
    }
    return 0L
}

private fun JSONObject.flexibleInt(vararg keys: String): Int =
    flexibleLong(*keys).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

private fun JSONObject.firstNonBlank(vararg keys: String): String? {
    keys.forEach { key ->
        val value = optString(key, "").trim()
        if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) return value
    }
    return null
}

private inline fun JSONArray.forEachString(block: (String) -> Unit) {
    for (index in 0 until length()) {
        val value = optString(index, "").trim()
        if (value.isNotEmpty()) block(value)
    }
}

private fun normalizeQQMusicImageUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> value.replaceFirst("http://", "https://")
        else -> value
    }
}

private fun stripJsonpWrapper(raw: String): String {
    val value = raw.trim()
    if (!value.startsWith("{") && '(' in value && value.endsWith(")")) {
        return value.substringAfter('(').substringBeforeLast(')').trim().removeSuffix(";")
    }
    return value
}
