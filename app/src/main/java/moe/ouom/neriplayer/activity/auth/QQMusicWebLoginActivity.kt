package moe.ouom.neriplayer.activity.auth

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/YGHFv/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 */

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.enableNeriEdgeToEdge
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.network.security.hostMatchesAnyDomain
import moe.ouom.neriplayer.network.security.isAllowedMainFrameRequest
import moe.ouom.neriplayer.network.weblogin.ForegroundWebLoginGuard
import moe.ouom.neriplayer.ui.feedback.showNeriViewSnackbar
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone
import org.json.JSONObject

/**
 * QQ 音乐登录使用独立 WebView 进程，避免 QQ 登录域与主进程 Cookie 相互污染。
 * 登录成功后返回 Map<String,String> 形式的 Cookie JSON。
 */
class QQMusicWebLoginActivity : ComponentActivity() {

    companion object {
        // QQMusicQrLoginActivity forwards this result unchanged to Settings.
        const val RESULT_COOKIE = "result_cookie_map_json"
        private const val LOG_TAG = "NERI-QQMusicLogin"
        private const val LOGIN_URL = "https://y.qq.com/"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val DESKTOP_VIEWPORT_SCRIPT =
            "(function(){" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "m.content='width=1280, initial-scale=0.32, maximum-scale=2, user-scalable=yes';" +
                "})()"

        private val ALLOWED_LOGIN_DOMAINS = setOf(
            "qq.com",
            "weixin.qq.com"
        )

        private val ALL_COOKIE_SOURCES = listOf(
            "https://y.qq.com/",
            "https://music.qq.com/"
        )

        private val QQ_COOKIE_SOURCES = listOf(
            "https://qq.com/",
            "https://ptlogin2.qq.com/",
            "https://graph.qq.com/",
            "https://wx.qq.com/",
            "https://weixin.qq.com/"
        )

        private val IMPORTANT_COOKIE_KEYS = setOf(
            "uin",
            "qqmusic_uin",
            "wxuin",
            "qqmusic_key",
            "qm_keyst",
            "musickey",
            "login_type",
            "tmeLoginType",
            "euin",
            "p_uin",
            "p_skey",
            "skey",
            "qqmusic_privilege",
            "psrf_musickey_createtime",
            "psrf_access_token_expiresAt",
            "psrf_qqunionid",
            "psrf_qqopenid",
            "psrf_qqaccess_token",
            "wxopenid",
            "wxunionid",
            "wxrefresh_token"
        )
    }

    private lateinit var webView: WebView
    private lateinit var toolbar: MaterialToolbar
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var hasReturned = false
    private var loginContextReady = false
    private val observedCookies = linkedMapOf<String, String>()
    private val popupWebViews = linkedSetOf<WebView>()
    private val webBackNavigation = WebLoginBackNavigation(
        canGoBack = { this::webView.isInitialized && webView.canGoBack() },
        goBack = { webView.goBack() }
    )
    private val loginCompletionWatcher = WebLoginCompletionWatcher(::maybeReturnIfLoggedIn)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (finishIfWebViewUnavailable()) return
        lockPortraitIfPhone()
        enableNeriEdgeToEdge()
        NPLogger.d(LOG_TAG, "QQ Music login activity created")
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("qq_music")

        val root = CoordinatorLayout(this).apply {
            fitsSystemWindows = false
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE
                )
            )
        }
        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE
                )
            )
        }
        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.qq_music_web_login)
            setNavigationIcon(R.drawable.ic_arrow_back_24)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_netease_web_login)
            setOnMenuItemClickListener(::onToolbarMenu)
        }
        appBar.addView(toolbar)

        webView = WebView(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                behavior = AppBarLayout.ScrollingViewBehavior()
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = DESKTOP_USER_AGENT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(true)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = InnerClient()
            webChromeClient = InnerChromeClient()
        }
        webView.resumeTimers()

        root.addView(webView)
        root.addView(appBar)
        appBar.bringToFront()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBar.updatePadding(top = status.top)
            webView.updatePadding(bottom = nav.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, webBackNavigation.callback)
        loginCompletionWatcher.start()
        resetWebLoginContext("create")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resetWebLoginContext("newIntent")
    }

    override fun onPause() {
        if (this::webView.isInitialized) {
            CookieManager.getInstance().flush()
            webView.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.resumeTimers()
            webView.onResume()
        }
    }

    override fun onDestroy() {
        loginCompletionWatcher.stop()
        popupWebViews.toList().forEach(::destroyPopupWebView)
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        foregroundWebLoginToken?.close()
        foregroundWebLoginToken = null
        super.onDestroy()
    }

    private fun onToolbarMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            webView.reload()
            true
        }
        R.id.action_read_cookie -> {
            readAndReturnCookies()
            true
        }
        else -> false
    }

    private fun resetWebLoginContext(reason: String) {
        if (hasReturned || !this::webView.isInitialized) return
        loginContextReady = false
        synchronized(observedCookies) {
            observedCookies.clear()
        }
        loginCompletionWatcher.scheduleCheck(delayMs = 0L)
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.removeSessionCookies {
                WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                cookieManager.flush()
                loginContextReady = true
                NPLogger.d(LOG_TAG, "Loading QQ Music login reason=$reason url=$LOGIN_URL")
                webView.loadUrl(LOGIN_URL)
            }
        }
    }

    private fun readAndReturnCookies() {
        try {
            CookieManager.getInstance().flush()
            val cookies = readQQMusicCookies()
            if (!QQMusicAuthBundle(cookies).hasLoginCookies()) {
                showNeriViewSnackbar(
                    webView,
                    getString(CoreCommonR.string.snackbar_cookie_empty),
                    Snackbar.LENGTH_SHORT
                )
                return
            }
            returnCookies(cookies)
        } catch (error: Throwable) {
            showNeriViewSnackbar(
                webView,
                getString(
                    CoreCommonR.string.snackbar_read_failed,
                    error.message ?: error.javaClass.simpleName
                ),
                Snackbar.LENGTH_LONG
            )
        }
    }

    private fun maybeReturnIfLoggedIn(): Boolean {
        if (hasReturned) return true
        if (!loginContextReady) return false
        CookieManager.getInstance().flush()
        val cookies = readQQMusicCookies()
        if (!QQMusicAuthBundle(cookies).hasLoginCookies()) return false
        returnCookies(cookies)
        return true
    }

    private fun returnCookies(cookies: Map<String, String>) {
        if (hasReturned) return
        hasReturned = true
        val json = JSONObject(cookies as Map<*, *>).toString()
        NPLogger.d(LOG_TAG, "Login OK, cookie keys=${cookies.keys}")
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        finish()
    }

    private fun readQQMusicCookies(): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        val result = linkedMapOf<String, String>()
        synchronized(observedCookies) {
            result.putAll(observedCookies)
        }
        ALL_COOKIE_SOURCES.forEach { url ->
            appendCookies(result, cookieManager.getCookie(url).orEmpty())
        }
        QQ_COOKIE_SOURCES.forEach { url ->
            appendCookies(
                result,
                cookieManager.getCookie(url).orEmpty(),
                allowedKeys = IMPORTANT_COOKIE_KEYS
            )
        }
        return result
    }

    private fun appendCookies(
        destination: MutableMap<String, String>,
        rawCookieHeader: String,
        allowedKeys: Set<String>? = null
    ) {
        rawCookieHeader.split(';').forEach { segment ->
            val pair = segment.trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) return@forEach
            val key = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (key.isEmpty() || value.isEmpty()) return@forEach
            if (allowedKeys != null && key !in allowedKeys) return@forEach
            destination.putIfAbsent(key, value)
        }
    }

    private inner class InnerClient : WebViewClient() {
        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            webBackNavigation.refresh()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val currentRequest = request ?: return false
            if (!isAllowedMainFrameRequest(currentRequest) { isAllowedLoginUri(it) }) {
                NPLogger.w(LOG_TAG, "Blocked unexpected navigation: ${currentRequest.url}")
                return true
            }
            if (currentRequest.isForMainFrame) {
                loginCompletionWatcher.scheduleCheck()
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val currentRequest = request
            if (currentRequest != null &&
                hostMatchesAnyDomain(currentRequest.url.host, ALLOWED_LOGIN_DOMAINS)
            ) {
                val cookieHeader = currentRequest.requestHeaders
                    .entries
                    .firstOrNull { (name, _) -> name.equals("Cookie", ignoreCase = true) }
                    ?.value
                    .orEmpty()
                synchronized(observedCookies) {
                    appendCookies(
                        observedCookies,
                        cookieHeader,
                        allowedKeys = IMPORTANT_COOKIE_KEYS
                    )
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            applyDesktopViewport(view)
            loginCompletionWatcher.scheduleCheck()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            applyDesktopViewport(view)
            CookieManager.getInstance().flush()
            loginCompletionWatcher.scheduleCheck()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                NPLogger.w(
                    LOG_TAG,
                    "Web error code=${error?.errorCode} desc=${error?.description} url=${request.url}"
                )
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true) {
                NPLogger.w(
                    LOG_TAG,
                    "HTTP error status=${errorResponse?.statusCode} url=${request.url}"
                )
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            NPLogger.e(LOG_TAG, "SSL error: $error")
            handler?.cancel()
        }
    }

    private inner class InnerChromeClient : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val result = resultMsg ?: return false
            val transport = result.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(this@QQMusicWebLoginActivity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = DESKTOP_USER_AGENT
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webChromeClient = InnerChromeClient()
                webViewClient = object : WebViewClient() {
                    private var forwarded = false

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = forwardPopup(view, request?.url)

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (url != null) forwardPopup(view, url.toUri())
                    }

                    private fun forwardPopup(source: WebView?, uri: Uri?): Boolean {
                        if (forwarded) return true
                        val resolved = uri ?: return false
                        forwarded = true
                        if (isAllowedLoginUri(resolved)) {
                            webView.loadUrl(resolved.toString())
                        } else {
                            NPLogger.w(LOG_TAG, "Blocked unexpected popup navigation: $resolved")
                        }
                        view?.post { destroyPopupWebView(source) }
                        return true
                    }
                }
            }
            popupWebViews += popup
            transport.webView = popup
            result.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            destroyPopupWebView(window)
            super.onCloseWindow(window)
        }
    }

    private fun destroyPopupWebView(view: WebView?) {
        val popup = view ?: return
        if (!popupWebViews.remove(popup)) return
        popup.stopLoading()
        (popup.parent as? ViewGroup)?.removeView(popup)
        popup.destroy()
    }

    private fun applyDesktopViewport(view: WebView?) {
        view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
    }

    private fun isAllowedLoginUri(uri: Uri?): Boolean {
        val resolvedUri = uri ?: return false
        if (resolvedUri.toString() == "about:blank") return true
        if (!resolvedUri.scheme.equals("https", ignoreCase = true)) return false
        return hostMatchesAnyDomain(resolvedUri.host, ALLOWED_LOGIN_DOMAINS)
    }
}
