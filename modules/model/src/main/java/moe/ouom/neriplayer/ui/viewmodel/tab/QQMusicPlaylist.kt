package moe.ouom.neriplayer.ui.viewmodel.tab

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicPlaylistKind

@Parcelize
data class QQMusicPlaylist(
    val id: Long,
    val dirId: Long,
    val title: String,
    val coverUrl: String,
    val trackCount: Int,
    val playCount: Long,
    val kind: QQMusicPlaylistKind
) : Parcelable
