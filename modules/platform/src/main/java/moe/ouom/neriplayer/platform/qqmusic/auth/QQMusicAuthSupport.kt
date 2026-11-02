package moe.ouom.neriplayer.platform.qqmusic.auth

import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle

private val QQ_MUSIC_COOKIE_NAME_REGEX = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

internal data class QQMusicCookieValidation(
    val sanitizedCookies: Map<String, String>,
    val rejectedKeys: List<String>
) {
    val isAccepted: Boolean get() = sanitizedCookies.isNotEmpty()
}

internal fun validateAndSanitizeQQMusicCookies(
    cookies: Map<String, String>
): QQMusicCookieValidation {
    val sanitized = linkedMapOf<String, String>()
    val rejected = linkedSetOf<String>()
    cookies.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        val rejectedKey = key.ifBlank { "<blank>" }
        when {
            key.isBlank() -> rejected += rejectedKey
            !QQ_MUSIC_COOKIE_NAME_REGEX.matches(key) -> rejected += rejectedKey
            value.isBlank() -> rejected += rejectedKey
            value.any(Char::isISOControl) -> rejected += rejectedKey
            ';' in value -> rejected += rejectedKey
            else -> sanitized[key] = value
        }
    }
    return QQMusicCookieValidation(
        sanitizedCookies = sanitized,
        rejectedKeys = rejected.toList()
    )
}

internal fun evaluateQQMusicAuthHealth(
    bundle: QQMusicAuthBundle,
    now: Long = System.currentTimeMillis()
): SavedCookieAuthHealth {
    val normalized = bundle.normalized(savedAt = bundle.savedAt)
    if (!normalized.hasLoginCookies()) {
        return SavedCookieAuthHealth(
            state = SavedCookieAuthState.Missing,
            savedAt = normalized.savedAt,
            checkedAt = now
        )
    }
    val savedAt = normalized.savedAt
    return SavedCookieAuthHealth(
        state = SavedCookieAuthState.Valid,
        savedAt = savedAt,
        checkedAt = now,
        ageMs = if (savedAt > 0L) {
            (now - savedAt).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        },
        loginCookieKeys = listOfNotNull(
            normalized.musicId?.let { "uin" },
            normalized.musicKey?.let { "qqmusic_key" }
        )
    )
}
