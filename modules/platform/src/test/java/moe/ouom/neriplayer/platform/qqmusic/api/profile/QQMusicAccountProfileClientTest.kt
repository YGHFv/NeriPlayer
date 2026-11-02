package moe.ouom.neriplayer.platform.qqmusic.api.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicAccountProfileClientTest {

    @Test
    fun `profile parser accepts homepage and nested creator payloads`() {
        val homepage = parseQQMusicAccountProfile(
            raw = """
                {
                  "code": 0,
                  "data": {
                    "hostuin": "10001",
                    "hostname": "Listener",
                    "headurl": "//y.gtimg.cn/avatar.jpg"
                  }
                }
            """.trimIndent(),
            fallbackMusicId = "fallback"
        )
        val nested = parseQQMusicAccountProfile(
            raw = """
                {
                  "data": {
                    "creator": {
                      "uin": "10002",
                      "nick": "Creator",
                      "headpic": "https://y.gtimg.cn/creator.jpg"
                    }
                  }
                }
            """.trimIndent(),
            fallbackMusicId = "fallback"
        )

        assertEquals("10001", homepage?.musicId)
        assertEquals("Listener", homepage?.nickname)
        assertEquals("https://y.gtimg.cn/avatar.jpg", homepage?.avatarUrl)
        assertEquals("10002", nested?.musicId)
        assertEquals("Creator", nested?.nickname)
        assertEquals("https://y.gtimg.cn/creator.jpg", nested?.avatarUrl)
    }

    @Test
    fun `profile parser accepts modern login user info payload`() {
        val profile = parseQQMusicAccountProfile(
            raw = """
                {
                  "code": 0,
                  "req_0": {
                    "code": 0,
                    "data": {
                      "info": {
                        "nick": "Modern Listener",
                        "headurl": "https://y.gtimg.cn/modern.jpg"
                      }
                    }
                  }
                }
            """.trimIndent(),
            fallbackMusicId = "10003"
        )

        assertEquals("10003", profile?.musicId)
        assertEquals("Modern Listener", profile?.nickname)
        assertEquals("https://y.gtimg.cn/modern.jpg", profile?.avatarUrl)
    }

    @Test
    fun `profile parser rejects error and blank identity responses`() {
        assertNull(
            parseQQMusicAccountProfile(
                raw = """{"code":1000,"data":{"hostuin":"1","hostname":"x"}}""",
                fallbackMusicId = "1"
            )
        )
        assertNull(
            parseQQMusicAccountProfile(
                raw = """{"data":{"hostname":"x"}}""",
                fallbackMusicId = ""
            )
        )
    }
}
