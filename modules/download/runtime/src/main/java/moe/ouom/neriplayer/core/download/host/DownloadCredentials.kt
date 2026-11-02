package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle

interface DownloadCredentials {
    fun biliCookies(): Map<String, String>
    fun youtubeAuth(): YouTubeAuthBundle
    fun qqMusicAuth(): QQMusicAuthBundle
}
