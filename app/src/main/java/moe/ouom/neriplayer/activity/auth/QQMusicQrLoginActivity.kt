package moe.ouom.neriplayer.activity.auth

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.enableNeriEdgeToEdge
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginChannel
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginCheckResult
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginSession
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicQrLoginStatus
import moe.ouom.neriplayer.network.weblogin.ForegroundWebLoginGuard
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicQrLoginClient
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

internal fun shouldPollQqMusicQrLogin(
    lifecycleState: Lifecycle.State,
    hasReturned: Boolean
): Boolean = !hasReturned && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

class QQMusicQrLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "result_cookie_map_json"
        private const val LOG_TAG = "NERI-QQMusicQrLogin"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val QR_SIZE_DP = 216
    }

    private val qrClient by lazy { QQMusicQrLoginClient() }
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var pollJob: Job? = null
    private var hasReturned = false
    private var selectedChannel = QQMusicQrLoginChannel.QQ
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: MaterialButton
    private lateinit var webFallbackButton: MaterialButton
    private lateinit var qqChannelButton: MaterialButton
    private lateinit var wechatChannelButton: MaterialButton

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            hasReturned = true
            setResult(RESULT_OK, result.data)
            finish()
        } else if (!hasReturned) {
            startQrLogin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableNeriEdgeToEdge()
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("qq_music")
        buildLayout()
        startQrLogin()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        qrClient.reset()
        foregroundWebLoginToken?.close()
        foregroundWebLoginToken = null
        NPLogger.d(LOG_TAG, "QQ Music QR login activity destroyed")
        super.onDestroy()
    }

    private fun buildLayout() {
        val root = CoordinatorLayout(this).apply {
            fitsSystemWindows = false
        }
        val surface = root.materialColor(
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val onSurface = root.materialColor(
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK
        )
        val surfaceVariant = root.materialColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            Color.rgb(244, 241, 246)
        )
        val onSurfaceVariant = root.materialColor(
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.DKGRAY
        )
        val primary = root.materialColor(
            androidx.appcompat.R.attr.colorPrimary,
            Color.rgb(21, 147, 91)
        )
        val onPrimary = root.materialColor(
            com.google.android.material.R.attr.colorOnPrimary,
            Color.WHITE
        )
        val softPrimary = ColorUtils.blendARGB(surface, primary, 0.12f)
        val softSurface = ColorUtils.blendARGB(surface, surfaceVariant, 0.18f)

        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(softPrimary, softSurface, surface)
        )

        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
        }
        appBar.addView(
            MaterialToolbar(this).apply {
                title = getString(R.string.qq_music_qr_login)
                setNavigationIcon(R.drawable.ic_arrow_back_24)
                setNavigationOnClickListener { finish() }
                setBackgroundColor(Color.TRANSPARENT)
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 22.dp(), 24.dp(), 48.dp())
        }
        val qrCardSizePx = minOf(
            240.dp(),
            resources.displayMetrics.widthPixels - 96.dp()
        ).coerceAtLeast(204.dp())
        val qrImageSizePx = minOf(
            QR_SIZE_DP.dp(),
            qrCardSizePx - 24.dp()
        ).coerceAtLeast(180.dp())
        val actionWidthPx = minOf(
            420.dp(),
            resources.displayMetrics.widthPixels - 48.dp()
        ).coerceAtLeast(228.dp())

        val titleText = TextView(this).apply {
            text = getString(R.string.qq_music_qr_login_title)
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
        }
        val subtitleText = TextView(this).apply {
            text = getString(R.string.qq_music_qr_login_subtitle)
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(2.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }

        val channelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = fixedWidthWrapHeight(actionWidthPx)
        }
        qqChannelButton = MaterialButton(this).apply {
            text = getString(R.string.qq_music_qr_login_channel_qq)
            cornerRadius = 18.dp()
            minHeight = 46.dp()
            insetTop = 0
            insetBottom = 0
            setOnClickListener { switchChannel(QQMusicQrLoginChannel.QQ) }
        }
        wechatChannelButton = MaterialButton(this).apply {
            text = getString(R.string.qq_music_qr_login_channel_wechat)
            cornerRadius = 18.dp()
            minHeight = 46.dp()
            insetTop = 0
            insetBottom = 0
            setOnClickListener { switchChannel(QQMusicQrLoginChannel.WECHAT) }
        }
        channelRow.addView(
            qqChannelButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 5.dp()
            }
        )
        channelRow.addView(
            wechatChannelButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 5.dp()
            }
        )

        val qrCard = MaterialCardView(this).apply {
            radius = 28.dp().toFloat()
            cardElevation = 2.dp().toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = false
            preventCornerOverlap = true
            layoutParams = LinearLayout.LayoutParams(qrCardSizePx, qrCardSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        qrImage = ImageView(this).apply {
            background = roundedBackground(Color.WHITE, 22.dp())
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            layoutParams = FrameLayout.LayoutParams(
                qrImageSizePx,
                qrImageSizePx,
                Gravity.CENTER
            )
        }
        qrCard.addView(qrImage)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(primary)
            layoutParams = FrameLayout.LayoutParams(42.dp(), 42.dp(), Gravity.CENTER)
        }
        qrCard.addView(progressBar)

        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 15f
            setPadding(16.dp(), 9.dp(), 16.dp(), 9.dp())
            setTextColor(primary)
            background = roundedBackground(
                ColorUtils.blendARGB(surface, primary, 0.11f),
                20.dp()
            )
        }
        hintText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setLineSpacing(3.dp().toFloat(), 1f)
            setTextColor(onSurfaceVariant)
        }
        retryButton = MaterialButton(this).apply {
            text = getString(R.string.qq_music_qr_login_retry)
            cornerRadius = 20.dp()
            minHeight = 52.dp()
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(primary)
            setTextColor(onPrimary)
            setOnClickListener { startQrLogin() }
        }
        webFallbackButton = MaterialButton(this).apply {
            text = getString(R.string.qq_music_qr_login_web_fallback)
            cornerRadius = 20.dp()
            minHeight = 50.dp()
            insetTop = 0
            insetBottom = 0
            strokeWidth = 0
            backgroundTintList = ColorStateList.valueOf(
                ColorUtils.blendARGB(surface, primary, 0.10f)
            )
            setTextColor(primary)
            setOnClickListener { openWebFallback() }
        }

        content.addView(titleText, matchWidthWrapHeight())
        content.addVerticalSpace(8)
        content.addView(subtitleText, matchWidthWrapHeight())
        content.addVerticalSpace(16)
        content.addView(channelRow)
        content.addVerticalSpace(16)
        content.addView(qrCard)
        content.addVerticalSpace(14)
        content.addView(statusText, wrapContentCentered())
        content.addVerticalSpace(10)
        content.addView(hintText, fixedWidthWrapHeight(actionWidthPx))
        content.addVerticalSpace(16)
        content.addView(retryButton, fixedWidthWrapHeight(actionWidthPx))
        content.addVerticalSpace(10)
        content.addView(webFallbackButton, fixedWidthWrapHeight(actionWidthPx))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                behavior = AppBarLayout.ScrollingViewBehavior()
            }
            addView(content)
        }

        root.addView(scrollView)
        root.addView(appBar)
        appBar.bringToFront()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBar.updatePadding(top = status.top)
            scrollView.updatePadding(bottom = nav.bottom + 16.dp())
            insets
        }
        updateChannelButtons()
    }

    private fun switchChannel(channel: QQMusicQrLoginChannel) {
        if (selectedChannel == channel && pollJob?.isActive == true) return
        selectedChannel = channel
        updateChannelButtons()
        startQrLogin()
    }

    private fun updateChannelButtons() {
        val surface = qqChannelButton.materialColor(
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val primary = qqChannelButton.materialColor(
            androidx.appcompat.R.attr.colorPrimary,
            Color.rgb(21, 147, 91)
        )
        val onPrimary = qqChannelButton.materialColor(
            com.google.android.material.R.attr.colorOnPrimary,
            Color.WHITE
        )
        listOf(
            qqChannelButton to QQMusicQrLoginChannel.QQ,
            wechatChannelButton to QQMusicQrLoginChannel.WECHAT
        ).forEach { (button, channel) ->
            val selected = selectedChannel == channel
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) primary else ColorUtils.blendARGB(surface, primary, 0.10f)
            )
            button.setTextColor(if (selected) onPrimary else primary)
        }
    }

    private fun startQrLogin() {
        pollJob?.cancel()
        qrClient.reset()
        val channel = selectedChannel
        NPLogger.d(LOG_TAG, "Start QR login channel=$channel")
        pollJob = lifecycleScope.launch {
            setLoadingState(true)
            setStatus(getString(R.string.qq_music_qr_login_loading))
            hintText.text = getString(R.string.qq_music_qr_login_hint)
            qrImage.setImageDrawable(null)

            val session = try {
                withContext(Dispatchers.IO) { qrClient.createSession(channel) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                setLoadingState(false)
                setErrorStatus(
                    getString(R.string.qq_music_qr_login_failed, error.readableMessage())
                )
                NPLogger.w(LOG_TAG, "Create QR login session failed channel=$channel", error)
                return@launch
            }
            NPLogger.d(
                LOG_TAG,
                "QR session ready channel=${session.channel} id=${session.id.redacted()}"
            )
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(
                    session.imageBytes,
                    0,
                    session.imageBytes.size
                )
            }
            if (bitmap == null) {
                setLoadingState(false)
                setErrorStatus(
                    getString(
                        R.string.qq_music_qr_login_failed,
                        getString(R.string.qq_music_qr_login_invalid_image)
                    )
                )
                NPLogger.w(LOG_TAG, "QR image bitmap decode failed")
                return@launch
            }
            qrImage.setImageBitmap(bitmap)
            setLoadingState(false)
            setStatus(waitingText(session.channel))
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                pollQrLogin(session)
            }
        }
    }

    private suspend fun pollQrLogin(session: QQMusicQrLoginSession) {
        while (shouldPollQqMusicQrLogin(lifecycle.currentState, hasReturned)) {
            val check = try {
                withContext(Dispatchers.IO) { qrClient.checkLogin(session) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                setErrorStatus(
                    getString(R.string.qq_music_qr_login_failed, error.readableMessage())
                )
                NPLogger.w(LOG_TAG, "Check QR login failed channel=${session.channel}", error)
                return
            }
            when (check.status) {
                QQMusicQrLoginStatus.WAITING -> setStatus(waitingText(session.channel))
                QQMusicQrLoginStatus.SCANNED -> setStatus(
                    getString(R.string.qq_music_qr_login_scanned)
                )
                QQMusicQrLoginStatus.CONFIRMED -> {
                    finishWithCookies(check)
                    return
                }
                QQMusicQrLoginStatus.REFUSED -> {
                    setErrorStatus(getString(R.string.qq_music_qr_login_refused))
                    return
                }
                QQMusicQrLoginStatus.EXPIRED -> {
                    setErrorStatus(getString(R.string.qq_music_qr_login_expired))
                    return
                }
            }
            delay(POLL_INTERVAL_MS.milliseconds)
        }
    }

    private fun finishWithCookies(check: QQMusicQrLoginCheckResult) {
        val cookies = check.cookies
        if (!QQMusicAuthBundle(cookies).hasLoginCookies()) {
            setErrorStatus(getString(R.string.qq_music_qr_login_cookie_incomplete))
            NPLogger.w(LOG_TAG, "QR login confirmed but credentials are incomplete")
            return
        }
        hasReturned = true
        val json = JSONObject().apply {
            cookies.forEach { (key, value) -> put(key, value) }
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d(
            LOG_TAG,
            "QR login OK channel=$selectedChannel cookieKeys=${cookies.keys}"
        )
        finish()
    }

    private fun openWebFallback() {
        pollJob?.cancel()
        qrClient.reset()
        NPLogger.d(LOG_TAG, "Open web fallback login")
        webLoginLauncher.launch(Intent(this, QQMusicWebLoginActivity::class.java))
    }

    private fun waitingText(channel: QQMusicQrLoginChannel): String =
        getString(
            when (channel) {
                QQMusicQrLoginChannel.QQ -> R.string.qq_music_qr_login_waiting_qq
                QQMusicQrLoginChannel.WECHAT -> R.string.qq_music_qr_login_waiting_wechat
            }
        )

    private fun setLoadingState(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        retryButton.isEnabled = !loading
        qqChannelButton.isEnabled = !loading
        wechatChannelButton.isEnabled = !loading
        webFallbackButton.isEnabled = true
    }

    private fun setStatus(text: String) {
        statusText.text = text
        val surface = statusText.materialColor(
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val primary = statusText.materialColor(
            androidx.appcompat.R.attr.colorPrimary,
            Color.rgb(21, 147, 91)
        )
        statusText.setTextColor(primary)
        statusText.background = roundedBackground(
            ColorUtils.blendARGB(surface, primary, 0.11f),
            20.dp()
        )
    }

    private fun setErrorStatus(text: String) {
        statusText.text = text
        val error = statusText.materialColor(
            androidx.appcompat.R.attr.colorError,
            Color.RED
        )
        val surface = statusText.materialColor(
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        statusText.setTextColor(error)
        statusText.background = roundedBackground(
            ColorUtils.blendARGB(surface, error, 0.12f),
            20.dp()
        )
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun LinearLayout.addVerticalSpace(heightDp: Int) {
        addView(
            View(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightDp.dp()
            )
        )
    }

    private fun matchWidthWrapHeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun fixedWidthWrapHeight(widthPx: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun wrapContentCentered(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun View.materialColor(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(this, attr, fallback)

    private fun roundedBackground(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).roundToInt()
}

private fun String.redacted(): String =
    if (length <= 8) "***" else "${take(4)}...${takeLast(4)}"
