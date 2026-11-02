package moe.ouom.neriplayer.data.model.qqmusic.auth

val QQ_MUSIC_LOGIN_ID_COOKIE_KEYS = listOf(
    "uin",
    "qqmusic_uin",
    "wxuin"
)

val QQ_MUSIC_LOGIN_KEY_COOKIE_KEYS = listOf(
    "qqmusic_key",
    "qm_keyst",
    "musickey"
)

data class QQMusicAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    val musicId: String?
        get() = QQ_MUSIC_LOGIN_ID_COOKIE_KEYS
            .firstNotNullOfOrNull { key ->
                cookies[key]?.normalizeQQMusicId()?.takeIf(String::isNotBlank)
            }

    val musicKey: String?
        get() = QQ_MUSIC_LOGIN_KEY_COOKIE_KEYS
            .firstNotNullOfOrNull { key -> cookies[key]?.trim()?.takeIf(String::isNotBlank) }

    val encryptedUin: String?
        get() = cookies["euin"]?.trim()?.takeIf(String::isNotBlank)

    val loginType: Int
        // tmeLoginType is the credential type used by QQ Music APIs. On the
        // official web login QQ accounts have login_type=1 and tmeLoginType=2.
        get() = listOf("tmeLoginType", "login_type")
            .firstNotNullOfOrNull { key ->
                cookies[key]?.trim()?.toIntOrNull()?.takeIf { it == 1 || it == 2 }
            }
            ?: if (cookies["wxuin"]?.isNotBlank() == true &&
                cookies["uin"].isNullOrBlank()
            ) 1 else 2

    fun hasLoginCookies(): Boolean = musicId != null && musicKey != null

    fun normalized(savedAt: Long = this.savedAt): QQMusicAuthBundle {
        val normalizedCookies = LinkedHashMap<String, String>()
        cookies.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) normalizedCookies[key] = value
        }
        return copy(cookies = normalizedCookies, savedAt = savedAt)
    }
}

data class QQMusicAccountProfile(
    val musicId: String,
    val nickname: String,
    val avatarUrl: String?
)

private fun String.normalizeQQMusicId(): String {
    val value = trim()
    val digits = value.removePrefix("o").filter(Char::isDigit)
    return digits.takeIf(String::isNotBlank) ?: value
}
