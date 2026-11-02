package moe.ouom.neriplayer.platform.qqmusic.auth

import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicAuthSupportTest {

    @Test
    fun `cookie validation drops malformed names values and separators`() {
        val result = validateAndSanitizeQQMusicCookies(
            linkedMapOf(
                "uin" to " 10001 ",
                "qm_keyst" to " key ",
                "bad name" to "value",
                "empty" to "",
                "control" to "a\nb",
                "inject" to "value; Domain=evil.test"
            )
        )

        assertEquals(mapOf("uin" to "10001", "qm_keyst" to "key"), result.sanitizedCookies)
        assertEquals(
            listOf("bad name", "empty", "control", "inject"),
            result.rejectedKeys
        )
        assertTrue(result.isAccepted)
    }

    @Test
    fun `auth health distinguishes missing and complete login cookies`() {
        val missing = evaluateQQMusicAuthHealth(
            QQMusicAuthBundle(cookies = mapOf("uin" to "10001")),
            now = 1_000L
        )
        val valid = evaluateQQMusicAuthHealth(
            QQMusicAuthBundle(
                cookies = mapOf("uin" to "10001", "qm_keyst" to "key"),
                savedAt = 500L
            ),
            now = 1_000L
        )

        assertEquals(SavedCookieAuthState.Missing, missing.state)
        assertEquals(SavedCookieAuthState.Valid, valid.state)
        assertEquals(500L, valid.savedAt)
        assertEquals(500L, valid.ageMs)
        assertFalse(valid.loginCookieKeys.isEmpty())
    }
}
