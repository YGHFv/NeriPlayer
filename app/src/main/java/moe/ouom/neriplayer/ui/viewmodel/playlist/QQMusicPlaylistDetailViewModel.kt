package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.netease.collection.NeteaseCollectionDetailUiState
import moe.ouom.neriplayer.data.model.netease.collection.NeteaseCollectionHeader
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicSong
import moe.ouom.neriplayer.platform.qqmusic.api.client.QQMusicAuthenticationRequiredException
import moe.ouom.neriplayer.platform.qqmusic.api.client.QQMusicClient
import moe.ouom.neriplayer.ui.viewmodel.tab.QQMusicPlaylist

class QQMusicPlaylistDetailViewModel internal constructor(
    application: Application,
    private val client: QQMusicClient
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppContainer.qqMusicClient)

    private val _uiState = MutableStateFlow(NeteaseCollectionDetailUiState())
    val uiState: StateFlow<NeteaseCollectionDetailUiState> = _uiState

    private var currentPlaylist: QQMusicPlaylist? = null
    private var currentOfflineMode: Boolean = false
    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L

    fun startPlaylist(
        playlist: QQMusicPlaylist,
        offlineMode: Boolean = false
    ) {
        currentPlaylist = playlist
        currentOfflineMode = offlineMode
        loadJob?.cancel()
        val generation = ++loadGeneration
        _uiState.value = NeteaseCollectionDetailUiState(
            loading = !offlineMode,
            header = NeteaseCollectionHeader(
                id = playlist.id,
                isAlbum = false,
                name = playlist.title,
                coverUrl = playlist.coverUrl,
                playCount = playlist.playCount,
                trackCount = playlist.trackCount
            )
        )
        if (offlineMode) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                error = PlaylistLoadFailure.Offline
            )
            return
        }
        loadJob = viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    client.getPlaylistDetail(
                        playlistId = playlist.id,
                        dirId = playlist.dirId
                    )
                }
                if (generation != loadGeneration) return@launch
                _uiState.value = detail.toUiState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: QQMusicAuthenticationRequiredException) {
                if (generation != loadGeneration) return@launch
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = PlaylistLoadFailure.Network
                )
            } catch (error: Exception) {
                if (generation != loadGeneration) return@launch
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = if (error is java.io.IOException) {
                        PlaylistLoadFailure.Network
                    } else {
                        PlaylistLoadFailure.Unknown
                    }
                )
            }
        }
    }

    fun retry(
        offlineMode: Boolean = currentOfflineMode,
        playlist: QQMusicPlaylist? = currentPlaylist
    ) {
        playlist ?: return
        startPlaylist(playlist, offlineMode)
    }

    fun updateOfflineMode(offlineMode: Boolean) {
        if (currentOfflineMode == offlineMode) return
        currentOfflineMode = offlineMode
        retry(offlineMode)
    }

    fun stop() {
        loadGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        currentPlaylist = null
        _uiState.value = _uiState.value.copy(loading = false)
    }
}

internal fun QQMusicPlaylistDetail.toUiState(): NeteaseCollectionDetailUiState {
    return NeteaseCollectionDetailUiState(
        loading = false,
        error = null,
        header = NeteaseCollectionHeader(
            id = id,
            isAlbum = false,
            name = name,
            coverUrl = coverUrl,
            playCount = playCount,
            trackCount = trackCount.takeIf { it > 0 } ?: tracks.size
        ),
        tracks = tracks.map(QQMusicSong::toSongItem)
    )
}

internal fun QQMusicSong.toSongItem(): SongItem {
    return SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        originalCoverUrl = coverUrl,
        channelId = ListenTogetherChannels.QQ_MUSIC,
        audioId = mid,
        subAudioId = mediaMid.takeIf { it.isNotBlank() }
    )
}
