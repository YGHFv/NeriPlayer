package moe.ouom.neriplayer.data.model.qqmusic.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class QQMusicSearchResponse(
    val code: Int = 0,
    val subcode: Int = 0,
    val data: QQMusicSearchData?
)
@Serializable data class QQMusicSearchData(val song: QQMusicSearchSong?)
@Serializable data class QQMusicSearchSong(
    val list: List<QQMusicSongSummary>?,
    @SerialName("totalnum") val total: Int? = null
)
@Serializable data class QQMusicSongSummary(
    @SerialName("songmid") val songMid: String,
    @SerialName("songid") val songId: Long? = null,
    @SerialName("songname") val songName: String,
    val singer: List<QQMusicArtist>,
    @SerialName("albummid") val albumMid: String?,
    @SerialName("albumname") val albumName: String?,
    val interval: Long, // 歌曲时长 (秒)
    @SerialName("strMediaMid") val mediaMid: String? = null,
    val file: QQMusicSongFile? = null
)

@Serializable data class QQMusicArtist(val name: String)
@Serializable data class QQMusicSongFile(@SerialName("media_mid") val mediaMid: String? = null)

@Serializable data class QQMusicDetailResponse(val data: QQMusicDetailData?)
@Serializable data class QQMusicDetailData(@SerialName("track_info") val trackInfo: QQMusicTrackInfo?)
@Serializable data class QQMusicTrackInfo(
    val mid: String,
    val name: String,
    val singer: List<QQMusicArtist>,
    val album: QQMusicAlbum,
    val interval: Long = 0L
)
@Serializable data class QQMusicAlbum(val name: String, val mid: String)

/**
 * 没有歌词时接口整个字段都不下发
 *
 * 可空但缺默认值在 kotlinx.serialization 里仍算必填, 会直接抛 MissingFieldException
 */
@Serializable data class QQMusicLyricResponse(
    val lyric: String? = null,
    val trans: String? = null
)

@Serializable data class QQMusicLyricContainer(
    val req: QQMusicLyricEnvelope? = null
)

@Serializable data class QQMusicLyricEnvelope(
    val code: Int = 0,
    val data: QQMusicLyricResponse? = null
)
