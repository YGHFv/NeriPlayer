package moe.ouom.neriplayer.data.model.qqmusic.library

enum class QQMusicPlaylistKind {
    CREATED,
    FAVORITE,
    LIKED
}

data class QQMusicLibraryPlaylist(
    val id: Long,
    val dirId: Long,
    val name: String,
    val coverUrl: String,
    val trackCount: Int,
    val playCount: Long,
    val kind: QQMusicPlaylistKind
)

data class QQMusicLibraryPlaylistsResult(
    val playlists: List<QQMusicLibraryPlaylist>,
    val warning: String? = null
)

data class QQMusicSong(
    val id: Long,
    val mid: String,
    val mediaMid: String,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumMid: String,
    val durationMs: Long,
    val coverUrl: String?,
    val size128: Long = 0L,
    val size192Ogg: Long = 0L,
    val size320: Long = 0L,
    val sizeFlac: Long = 0L,
    val sizeOgg320: Long = 0L,
    val sizeOgg640: Long = 0L,
    val sizeDolby: Long = 0L,
    val previewMediaMid: String = "",
    val masterMediaMid: String = "",
    val hiresMediaMid: String = "",
    val atmos71MediaMid: String = "",
    val dtsMediaMid: String = "",
    val sizeMaster: Long = 0L,
    val sizeHires: Long = 0L,
    val sizeAtmos51: Long = 0L,
    val sizeAtmos71: Long = 0L,
    val sizeDtsx: Long = 0L
)

data class QQMusicPlaylistDetail(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val playCount: Long,
    val trackCount: Int,
    val tracks: List<QQMusicSong>
)

data class QQMusicAudioStream(
    val url: String,
    val candidateUrls: List<String> = emptyList(),
    val mimeType: String,
    val fileExtensionHint: String,
    val qualityKey: String,
    val contentLength: Long? = null,
    val isPreviewClip: Boolean = false
)

data class QQMusicRecommendationShelf(
    val title: String,
    val songs: List<QQMusicSong>
)

data class QQMusicRecommendationPlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val playCount: Long,
    val trackCount: Int
)

data class QQMusicRecommendationFeed(
    val shelves: List<QQMusicRecommendationShelf>,
    val playlists: List<QQMusicRecommendationPlaylist>
)
