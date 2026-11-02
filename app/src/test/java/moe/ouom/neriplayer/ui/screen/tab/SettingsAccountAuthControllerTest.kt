package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.runtime.mutableIntStateOf
import moe.ouom.neriplayer.ui.i18n.UiText
import androidx.compose.runtime.mutableStateOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountAuthController
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAuthDialogState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountPlatform
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.QQMusicAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.QQMusicAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class SettingsAccountAuthControllerTest {
    private val neteaseVm = mock(NeteaseAuthViewModel::class.java)
    private val biliVm = mock(BiliAuthViewModel::class.java)
    private val youtubeVm = mock(YouTubeAuthViewModel::class.java)
    private val qqMusicVm = mock(QQMusicAuthViewModel::class.java)
    private val dialogs = SettingsAuthDialogState(
        biliTab = mutableIntStateOf(0),
        neteaseTab = mutableIntStateOf(0),
        youtubeTab = mutableIntStateOf(0),
        qqMusicTab = mutableIntStateOf(0)
    )
    private val messages = mutableListOf<UiText?>()
    private val latestMessageAction = mutableStateOf<(UiText?) -> Unit>({ messages += it })
    private val controller = SettingsAccountAuthController(
        neteaseVm, biliVm, youtubeVm, qqMusicVm, dialogs, latestMessageAction
    )

    @Test
    fun `platform login opens only its own sheet and resets its selected tab`() {
        listOf(
            SettingsAccountPlatform.Netease,
            SettingsAccountPlatform.Bilibili,
            SettingsAccountPlatform.YouTube,
            SettingsAccountPlatform.QqMusic
        ).forEach { platform ->
            dialogs.showNeteaseSheet = false
            dialogs.showBiliSheet = false
            dialogs.showYouTubeSheet = false
            dialogs.showQQMusicSheet = false
            dialogs.neteaseTab.intValue = 2
            dialogs.biliTab.intValue = 3
            dialogs.youtubeTab.intValue = 4
            dialogs.qqMusicTab.intValue = 5

            controller.actions.openPlatformLogin(platform)

            assertEquals(platform == SettingsAccountPlatform.Netease, dialogs.showNeteaseSheet)
            assertEquals(platform == SettingsAccountPlatform.Bilibili, dialogs.showBiliSheet)
            assertEquals(platform == SettingsAccountPlatform.YouTube, dialogs.showYouTubeSheet)
            assertEquals(platform == SettingsAccountPlatform.QqMusic, dialogs.showQQMusicSheet)
            assertEquals(if (platform == SettingsAccountPlatform.Netease) 0 else 2, dialogs.neteaseTab.intValue)
            assertEquals(if (platform == SettingsAccountPlatform.Bilibili) 0 else 3, dialogs.biliTab.intValue)
            assertEquals(if (platform == SettingsAccountPlatform.YouTube) 0 else 4, dialogs.youtubeTab.intValue)
            assertEquals(if (platform == SettingsAccountPlatform.QqMusic) 0 else 5, dialogs.qqMusicTab.intValue)
        }
        assertEquals(listOf<UiText?>(null, null, null, null), messages)
        verifyNoInteractions(neteaseVm, biliVm, youtubeVm, qqMusicVm)
    }

    @Test
    fun `platform saved authorization opens only its own management dialog`() {
        listOf(
            SettingsAccountPlatform.Netease,
            SettingsAccountPlatform.Bilibili,
            SettingsAccountPlatform.YouTube,
            SettingsAccountPlatform.QqMusic
        ).forEach { platform ->
            dialogs.showNeteaseSavedCookieDialog = false
            dialogs.showBiliSavedCookieDialog = false
            dialogs.showYouTubeSavedCookieDialog = false
            dialogs.showQQMusicSavedCookieDialog = false

            controller.actions.openPlatformSavedAuthorization(platform)

            assertEquals(platform == SettingsAccountPlatform.Netease, dialogs.showNeteaseSavedCookieDialog)
            assertEquals(platform == SettingsAccountPlatform.Bilibili, dialogs.showBiliSavedCookieDialog)
            assertEquals(platform == SettingsAccountPlatform.YouTube, dialogs.showYouTubeSavedCookieDialog)
            assertEquals(platform == SettingsAccountPlatform.QqMusic, dialogs.showQQMusicSavedCookieDialog)
            assertFalse(dialogs.showNeteaseSheet)
            assertFalse(dialogs.showBiliSheet)
            assertFalse(dialogs.showYouTubeSheet)
            assertFalse(dialogs.showQQMusicSheet)
        }
        assertEquals(listOf<UiText?>(null, null, null, null), messages)
        verifyNoInteractions(neteaseVm, biliVm, youtubeVm, qqMusicVm)
    }

    @Test
    fun `login events own their sheet and success state`() {
        controller.actions.openNeteaseAtTab(2)
        controller.actions.openNeteaseSavedCookieDialog()
        controller.onNeteaseEvent(NeteaseAuthEvent.ShowSnack(UiText.Literal("netease error")))
        controller.onNeteaseEvent(NeteaseAuthEvent.AskConfirmSend("***1234"))
        assertEquals(2, dialogs.neteaseTab.intValue)
        assertTrue(dialogs.showNeteaseConfirmDialog)
        assertEquals("***1234", dialogs.confirmPhoneMasked)

        controller.onNeteaseEvent(NeteaseAuthEvent.LoginSuccess)
        assertFalse(dialogs.showNeteaseSheet)
        assertFalse(dialogs.showNeteaseSavedCookieDialog)
        assertEquals(CoreCommonR.string.settings_netease_login_success, dialogs.loginSuccessTitleRes)
        assertEquals(listOf(null, null, UiText.Literal("netease error"), null), messages)
        verify(neteaseVm).refreshAuthHealth()
        controller.actions.dismissLoginSuccess()
        assertEquals(null, dialogs.loginSuccessTitleRes)
    }

    @Test
    fun `bili and youtube actions keep their own tabs and credentials`() {
        controller.actions.openBiliAtTab(1)
        controller.actions.openBiliSavedCookieDialog()
        controller.onBiliEvent(BiliAuthEvent.ShowSnack(UiText.Literal("bili error")))
        controller.onBiliEvent(BiliAuthEvent.LoginSuccess)
        assertEquals(1, dialogs.biliTab.intValue)
        assertFalse(dialogs.showBiliSheet)
        assertFalse(dialogs.showBiliSavedCookieDialog)
        assertEquals(CoreCommonR.string.settings_bili_login_success, dialogs.loginSuccessTitleRes)
        verify(biliVm).refreshAuthHealth()

        controller.actions.openYouTubeAtTab(2)
        controller.actions.openYouTubeSavedCookieDialog()
        controller.onYouTubeEvent(YouTubeAuthEvent.ShowSnack(UiText.Literal("youtube error")))
        controller.onYouTubeEvent(YouTubeAuthEvent.LoginSuccess)
        assertEquals(2, dialogs.youtubeTab.intValue)
        assertFalse(dialogs.showYouTubeSheet)
        assertFalse(dialogs.showYouTubeSavedCookieDialog)
        assertEquals(CoreCommonR.string.settings_youtube_login_success, dialogs.loginSuccessTitleRes)
        assertEquals(listOf(null, null, UiText.Literal("bili error"), null, null, null, UiText.Literal("youtube error"), null), messages)
        verify(youtubeVm).refreshAuthHealth()

        controller.actions.openQQMusicAtTab(3)
        controller.actions.openQQMusicSavedCookieDialog()
        controller.onQQMusicEvent(QQMusicAuthEvent.ShowSnack(UiText.Literal("qq error")))
        controller.onQQMusicEvent(QQMusicAuthEvent.LoginSuccess)
        assertEquals(3, dialogs.qqMusicTab.intValue)
        assertFalse(dialogs.showQQMusicSheet)
        assertFalse(dialogs.showQQMusicSavedCookieDialog)
        assertEquals(CoreCommonR.string.settings_qq_music_login_success, dialogs.loginSuccessTitleRes)
        verify(qqMusicVm).refreshAuthHealth()
    }

    @Test
    fun `saved credential logout closes only its own dialog`() {
        controller.actions.openNeteaseSavedCookieDialog()
        controller.actions.openBiliSavedCookieDialog()
        controller.actions.openYouTubeSavedCookieDialog()
        controller.actions.openQQMusicSavedCookieDialog()

        controller.actions.logoutNetease()
        controller.actions.logoutBili()
        controller.actions.logoutYouTube()
        controller.actions.logoutQQMusic()

        assertFalse(dialogs.showNeteaseSavedCookieDialog)
        assertFalse(dialogs.showBiliSavedCookieDialog)
        assertFalse(dialogs.showYouTubeSavedCookieDialog)
        assertFalse(dialogs.showQQMusicSavedCookieDialog)
        verify(neteaseVm).clearCookies()
        verify(biliVm).clearCookies()
        verify(youtubeVm).clearAuth()
        verify(qqMusicVm).clearCookies()
    }

    @Test
    fun `event callback uses the latest inline message recipient`() {
        val replacementMessages = mutableListOf<UiText?>()
        latestMessageAction.value = { replacementMessages += it }

        controller.onYouTubeEvent(YouTubeAuthEvent.ShowSnack(UiText.Literal("updated")))

        assertTrue(messages.isEmpty())
        assertEquals(listOf(UiText.Literal("updated")), replacementMessages)
    }
}
