package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.QQMusicPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.QQMusicPlaylist

@Composable
fun QQMusicPlaylistDetailScreen(
    playlist: QQMusicPlaylist,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val vm: QQMusicPlaylistDetailViewModel = viewModel(
        key = "qqmusic_playlist_${playlist.id}_${playlist.dirId}",
        factory = viewModelFactory {
            initializer {
                QQMusicPlaylistDetailViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by vm.uiState.collectAsState()

    DisposableEffect(vm, playlist.id, playlist.dirId) {
        vm.startPlaylist(playlist, offlineMode)
        onDispose(vm::stop)
    }
    LaunchedEffect(vm, offlineMode) {
        vm.updateOfflineMode(offlineMode)
    }

    DetailScreen(
        ui = ui,
        playlistId = playlist.id,
        playlistSource = "qqMusic",
        initialCoverUrl = playlist.coverUrl,
        onRetry = { vm.retry(offlineMode, playlist) },
        onBack = {
            vm.stop()
            onBack()
        },
        onSongClick = onSongClick,
        offlineMode = offlineMode
    )
}
