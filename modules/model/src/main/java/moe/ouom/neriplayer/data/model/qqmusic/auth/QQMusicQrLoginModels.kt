package moe.ouom.neriplayer.data.model.qqmusic.auth

enum class QQMusicQrLoginChannel {
    QQ,
    WECHAT
}

data class QQMusicQrLoginSession(
    val id: String,
    val channel: QQMusicQrLoginChannel,
    val imageBytes: ByteArray,
    val expiresAtEpochMs: Long
)

enum class QQMusicQrLoginStatus {
    WAITING,
    SCANNED,
    CONFIRMED,
    REFUSED,
    EXPIRED
}

data class QQMusicQrLoginCheckResult(
    val status: QQMusicQrLoginStatus,
    val message: String = "",
    val cookies: Map<String, String> = emptyMap()
) {
    val isConfirmed: Boolean
        get() = status == QQMusicQrLoginStatus.CONFIRMED
}
