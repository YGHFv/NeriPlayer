package moe.ouom.neriplayer.activity.auth

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicQrLoginPollingPolicyTest {
    @Test
    fun `polls only while the screen is started and login has not returned`() {
        assertTrue(shouldPollQqMusicQrLogin(Lifecycle.State.STARTED, hasReturned = false))
        assertTrue(shouldPollQqMusicQrLogin(Lifecycle.State.RESUMED, hasReturned = false))
        assertFalse(shouldPollQqMusicQrLogin(Lifecycle.State.CREATED, hasReturned = false))
        assertFalse(shouldPollQqMusicQrLogin(Lifecycle.State.STARTED, hasReturned = true))
    }

    @Test
    fun `web fallback forwards the same cookie result key consumed by settings`() {
        assertEquals(
            QQMusicQrLoginActivity.RESULT_COOKIE,
            QQMusicWebLoginActivity.RESULT_COOKIE
        )
    }
}
