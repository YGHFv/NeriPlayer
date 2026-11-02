package moe.ouom.neriplayer.data.settings.playback

import moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_MOBILE_DATA_QQ_MUSIC_AUDIO_QUALITY
import java.util.Locale

internal const val DEFAULT_PLAYBACK_QQ_MUSIC_AUDIO_QUALITY = "exhigh"

private val QQ_MUSIC_AUDIO_QUALITIES = setOf(
    "standard",
    "higher",
    "exhigh",
    "lossless",
    "hires",
    "atmos51",
    "atmos71",
    "dolby",
    "dtsx",
    "master"
)

fun normalizeQQMusicAudioQuality(value: String?): String {
    val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
    return normalized.takeIf { it in QQ_MUSIC_AUDIO_QUALITIES }
        ?: DEFAULT_PLAYBACK_QQ_MUSIC_AUDIO_QUALITY
}

fun normalizeMobileDataQqMusicAudioQuality(value: String?): String {
    val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
    return normalized.takeIf { it in QQ_MUSIC_AUDIO_QUALITIES }
        ?: DEFAULT_MOBILE_DATA_QQ_MUSIC_AUDIO_QUALITY
}
