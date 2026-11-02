package moe.ouom.neriplayer.data.model.qqmusic.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicAuthModelsTest {

    @Test
    fun `qq id can be read from music specific or wechat cookies`() {
        val qq = QQMusicAuthBundle(
            cookies = mapOf("qqmusic_uin" to "o00123", "qqmusic_key" to "key")
        )
        val wechat = QQMusicAuthBundle(
            cookies = mapOf(
                "wxuin" to "456",
                "musickey" to "wx-key",
                "tmeLoginType" to "1"
            )
        )

        assertEquals("00123", qq.musicId)
        assertEquals(2, qq.loginType)
        assertEquals("456", wechat.musicId)
        assertEquals(1, wechat.loginType)
        assertTrue(qq.hasLoginCookies())
        assertTrue(wechat.hasLoginCookies())
    }

    @Test
    fun `login cookies require both id and key`() {
        assertFalse(QQMusicAuthBundle(cookies = mapOf("uin" to "123")).hasLoginCookies())
        assertFalse(QQMusicAuthBundle(cookies = mapOf("qm_keyst" to "key")).hasLoginCookies())
    }

    @Test
    fun `api login type takes precedence over web login type`() {
        val qqWebLogin = QQMusicAuthBundle(
            cookies = mapOf(
                "uin" to "123",
                "qqmusic_key" to "key",
                "login_type" to "1",
                "tmeLoginType" to "2"
            )
        )
        val wechatWebLogin = QQMusicAuthBundle(
            cookies = mapOf(
                "wxuin" to "456",
                "musickey" to "wx-key",
                "login_type" to "2",
                "tmeLoginType" to "1"
            )
        )

        assertEquals(2, qqWebLogin.loginType)
        assertEquals(1, wechatWebLogin.loginType)
    }
}
