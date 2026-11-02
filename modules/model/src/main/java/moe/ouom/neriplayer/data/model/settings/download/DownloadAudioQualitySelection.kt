package moe.ouom.neriplayer.data.model.settings.download

data class DownloadAudioQualitySelection(
    val neteaseQuality: String,
    val youtubeQuality: String,
    val biliQuality: String,
    val qqMusicQuality: String = "exhigh"
) {
    companion object
}
