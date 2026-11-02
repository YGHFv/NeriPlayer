package moe.ouom.neriplayer.core.player.metadata

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
 * File: moe.ouom.neriplayer.core.player.metadata/PlayerLyricsSupport
 * Created: 2025/8/17
 */

import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.server.isServerSong
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.lyrics.parser.normalizeLegacyLrcTimestamps
import org.json.JSONObject


fun extractPreferredNeteaseLyricContent(rawResponse: String): String {
    val payload = JSONObject(rawResponse)
    val yrc: String = payload.optJSONObject("yrc")?.optString("lyric").orEmpty()
    if (yrc.isNotBlank()) {
        return yrc
    }
    return normalizeLegacyLrcTimestamps(
        payload.optJSONObject("lrc")?.optString("lyric").orEmpty()
    )
}

internal fun extractTranslatedNeteaseLyricContent(rawResponse: String): String {
    val payload = JSONObject(rawResponse)
    return payload.optJSONObject("ytlrc")?.optString("lyric")
        ?: payload.optJSONObject("tlyric")?.optString("lyric")
        ?: ""
}

internal fun extractRomanizedNeteaseLyricContent(rawResponse: String): String {
    val payload = JSONObject(rawResponse)
    return normalizeLegacyLrcTimestamps(
        payload.optJSONObject("romalrc")?.optString("lyric").orEmpty()
    )
}

data class NeteaseLyricsCacheEntry(
    val preferredLyricText: String,
    val translatedLyricText: String = "",
    val romanizedLyricText: String,
    val preferredLyricEntries: List<LyricEntry>,
    val translatedLyricEntries: List<LyricEntry>,
    val romanizedLyricEntries: List<LyricEntry>,
    val rawResponse: String = ""
)

internal enum class LocalLyricOverrideState {
    ABSENT,
    CLEARED,
    PRESENT
}

internal fun resolveLocalLyricOverrideState(rawLyric: String?): LocalLyricOverrideState {
    return when {
        rawLyric == null -> LocalLyricOverrideState.ABSENT
        rawLyric.isBlank() -> LocalLyricOverrideState.CLEARED
        else -> LocalLyricOverrideState.PRESENT
    }
}

internal fun resolveLocalFirstLyricText(
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?
): String? {
    return localLyric ?: storedLyric ?: downloadedLyric
}

internal fun resolveManagedDownloadFirstLyricText(
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?
): String? {
    return downloadedLyric ?: localLyric ?: storedLyric
}

fun resolveLyricTextForPlayback(
    isManagedLocalDownload: Boolean,
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?
): String? {
    return if (isManagedLocalDownload) {
        resolveManagedDownloadFirstLyricText(
            localLyric = localLyric,
            storedLyric = storedLyric,
            downloadedLyric = downloadedLyric
        )
    } else {
        resolveLocalFirstLyricText(
            localLyric = localLyric,
            storedLyric = storedLyric,
            downloadedLyric = downloadedLyric
        )
    }
}

internal fun shouldLoadRemoteLyrics(song: SongItem): Boolean {
    return !song.isLocalSong() && !song.isServerSong()
}

fun shouldReadManagedDownloadLyrics(
    song: SongItem,
    isManagedLocalDownload: Boolean
): Boolean {
    return !song.isLocalSong() || isManagedLocalDownload
}
