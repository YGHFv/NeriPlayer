@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.activity.auth.QQMusicQrLoginActivity
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.viewmodel.auth.QQMusicAuthViewModel
import moe.ouom.neriplayer.common.R as CoreCommonR

@Composable
internal fun SettingsQQMusicAuthDialogs(
    showSheet: Boolean,
    initialTab: Int,
    onDismissSheet: () -> Unit,
    inlineMsg: UiText?,
    onInlineMsgChange: (UiText?) -> Unit,
    vm: QQMusicAuthViewModel,
    showSavedCookieDialog: Boolean = false,
    onDismissSavedCookieDialog: () -> Unit = {},
    onOpenSheetAtTab: (Int) -> Unit = {},
    onLogout: (() -> Unit)? = null
) {
    val context = LocalContext.current

    if (showSavedCookieDialog) {
        SavedCookieActionDialog(
            title = stringResource(CoreCommonR.string.settings_qq_music_saved_cookie_title),
            message = stringResource(CoreCommonR.string.settings_qq_music_saved_cookie_message),
            onDismiss = onDismissSavedCookieDialog,
            onContinueLogin = {
                onDismissSavedCookieDialog()
                onOpenSheetAtTab(0)
            },
            onLogout = {
                onDismissSavedCookieDialog()
                onLogout?.invoke()
            }
        )
    }

    if (!showSheet) return

    val authStorage = rememberAuthLoginStorageStatus(SettingsAccountPlatform.QqMusic)
    val qrLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(QQMusicQrLoginActivity.RESULT_COOKIE).orEmpty()
            vm.importCookiesFromMap(vm.parseJsonToMap(json))
        } else {
            onInlineMsgChange(UiText.Resource(CoreCommonR.string.settings_cookie_cancelled))
        }
    }

    SettingsCookieLoginSheet(
        title = stringResource(CoreCommonR.string.settings_qq_music),
        initialTab = initialTab,
        inlineMsg = inlineMsg?.asString(),
        onInlineMsgChange = { onInlineMsgChange(null) },
        onDismiss = onDismissSheet,
        browserTabLabel = stringResource(CoreCommonR.string.login_qr),
        browserButtonLabel = stringResource(CoreCommonR.string.login_start_qq_music_qr),
        browserHintContent = {
            Text(
                stringResource(CoreCommonR.string.settings_qq_music_login_browser_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        cookieLabel = stringResource(CoreCommonR.string.login_paste_qq_music_cookie_hint),
        onBrowserLogin = {
            onInlineMsgChange(null)
            AppContainer.pauseYouTubeBackgroundWebWorkForForegroundLogin()
            qrLoginLauncher.launch(Intent(context, QQMusicQrLoginActivity::class.java))
        },
        onSaveCookie = { rawCookie ->
            if (rawCookie.isBlank()) {
                onInlineMsgChange(UiText.Resource(CoreCommonR.string.auth_cookie_empty))
            } else {
                vm.importCookiesFromRaw(rawCookie)
            }
        },
        requiresCompatibilityStorageWarning = authStorage?.requiresCompatibilityWarning ?: false,
        isAuthStoragePersistent = authStorage?.isPersistent ?: false,
        isAuthStorageReady = authStorage != null
    )
}
