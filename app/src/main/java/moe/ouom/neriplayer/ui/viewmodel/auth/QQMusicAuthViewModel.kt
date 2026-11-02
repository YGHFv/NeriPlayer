package moe.ouom.neriplayer.ui.viewmodel.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.data.auth.web.WebLoginPlatform
import moe.ouom.neriplayer.data.auth.web.clearWebViewLoginState
import moe.ouom.neriplayer.network.http.parsing.parseRawCookieText
import moe.ouom.neriplayer.ui.i18n.UiText
import org.json.JSONObject

data class QQMusicAuthUiState(
    val health: SavedCookieAuthHealth = SavedCookieAuthHealth(),
    val hasSavedCookies: Boolean = false
)

sealed interface QQMusicAuthEvent {
    data class ShowSnack(val message: UiText) : QQMusicAuthEvent
    data object LoginSuccess : QQMusicAuthEvent
}

class QQMusicAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo by lazy { AppContainer.qqMusicAuthRepo }

    private val _uiState = MutableStateFlow(QQMusicAuthUiState())
    val uiState: StateFlow<QQMusicAuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<QQMusicAuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                QQMusicAuthUiState(
                    health = repo.getAuthHealth(),
                    hasSavedCookies = repo.getAuthOnce().hasLoginCookies()
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repo.authHealthFlow.collect { health ->
                _uiState.update { it.copy(health = health) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repo.authFlow.collect { bundle ->
                _uiState.update { it.copy(hasSavedCookies = bundle.hasLoginCookies()) }
            }
        }
    }

    fun refreshAuthHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.refreshHealth()
            _uiState.update { current ->
                current.copy(health = repo.getAuthHealthOnce())
            }
        }
    }

    fun clearCookies() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.clear()
                try {
                    clearWebViewLoginState(
                        getApplication(),
                        WebLoginPlatform.QQ_MUSIC
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 主进程不一定提供 WebView；独立登录进程随后仍会清理自身状态
                }
            } catch (_: IOException) {
                emitSnack(UiText.Resource(CoreCommonR.string.auth_storage_clear_failed))
                return@launch
            }
            emitSnack(UiText.Resource(CoreCommonR.string.auth_cookie_cleared))
        }
    }

    fun importCookiesFromRaw(raw: String) {
        val cookies = runCatching {
            if (raw.trimStart().startsWith("{")) parseCookieJson(raw)
            else parseRawCookieText(raw)
        }.getOrDefault(emptyMap())
        importCookiesFromMap(cookies)
    }

    fun importCookiesFromMap(cookies: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val bundle = QQMusicAuthBundle(
                cookies = cookies,
                savedAt = System.currentTimeMillis()
            )
            if (!bundle.hasLoginCookies()) {
                emitSnack(UiText.Resource(CoreCommonR.string.auth_cookie_invalid))
                return@launch
            }
            try {
                repo.saveCookies(cookies)
            } catch (_: IOException) {
                emitSnack(UiText.Resource(CoreCommonR.string.auth_storage_save_failed))
                return@launch
            }
            _uiState.value = QQMusicAuthUiState(
                health = repo.getAuthHealth(),
                hasSavedCookies = true
            )
            _events.send(QQMusicAuthEvent.LoginSuccess)
        }
    }

    fun parseJsonToMap(json: String): Map<String, String> = parseCookieJson(json)

    private fun emitSnack(message: UiText) {
        viewModelScope.launch {
            _events.send(QQMusicAuthEvent.ShowSnack(message))
        }
    }
}

private fun parseCookieJson(json: String): Map<String, String> = runCatching {
    val obj = JSONObject(json)
    buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, obj.optString(key, ""))
        }
    }
}.getOrDefault(emptyMap())
