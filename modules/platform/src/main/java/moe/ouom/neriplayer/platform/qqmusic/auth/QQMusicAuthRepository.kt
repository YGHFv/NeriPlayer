@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.qqmusic.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import org.json.JSONObject

private const val QQ_MUSIC_AUTH_PREFS = "qq_music_auth_secure_prefs"
private const val KEY_QQ_MUSIC_AUTH_BUNDLE = "qq_music_auth_bundle"

class QQMusicAuthRepository(private val context: Context) {
    private var encryptedPrefs: SharedPreferences
    private val _authFlow: MutableStateFlow<QQMusicAuthBundle>
    private val _cookieFlow: MutableStateFlow<Map<String, String>>
    private val _authHealthFlow: MutableStateFlow<SavedCookieAuthHealth>
    private val writeLock = Any()

    val authFlow: StateFlow<QQMusicAuthBundle>
        get() = _authFlow.asStateFlow()

    val cookieFlow: StateFlow<Map<String, String>>
        get() = _cookieFlow.asStateFlow()

    val authHealthFlow: StateFlow<SavedCookieAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    val requiresCompatibilityStorageWarning: Boolean
        get() = AccountPreferencesOpener.isCompatibilityStorage(encryptedPrefs)

    val isAuthStoragePersistent: Boolean
        get() = SecurePreferencesOpener.isPersistent(encryptedPrefs)

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _cookieFlow = MutableStateFlow(initialBundle.cookies)
        _authHealthFlow = MutableStateFlow(evaluateQQMusicAuthHealth(initialBundle))
    }

    fun getAuthOnce(): QQMusicAuthBundle = _authFlow.value

    fun getCookiesOnce(): Map<String, String> = _cookieFlow.value

    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value

    fun getAuthHealth(now: Long = System.currentTimeMillis()): SavedCookieAuthHealth =
        evaluateQQMusicAuthHealth(_authFlow.value, now)

    fun saveCookies(
        cookies: Map<String, String>,
        savedAt: Long = System.currentTimeMillis()
    ): Unit = synchronized(writeLock) {
        val validation = validateAndSanitizeQQMusicCookies(cookies)
        if (!validation.isAccepted) {
            throw IOException("No valid QQ Music cookies were provided")
        }
        val normalized = QQMusicAuthBundle(
            cookies = validation.sanitizedCookies,
            savedAt = savedAt
        ).normalized(savedAt = savedAt)
        if (!normalized.hasLoginCookies()) {
            throw IOException("QQ Music login cookies are incomplete")
        }
        if (!writeAuthBundle(normalized)) {
            throw IOException("Failed to save QQ Music account storage")
        }
        _authFlow.value = normalized
        _cookieFlow.value = normalized.cookies
        _authHealthFlow.value = evaluateQQMusicAuthHealth(normalized)
        NPLogger.d(
            "NERI-QQMusicAuthRepo",
            "Saved QQ Music cookies: keys=${normalized.cookies.keys.joinToString()}"
        )
    }

    fun clear(): Unit = synchronized(writeLock) {
        if (!writeAuthBundle(null)) {
            throw IOException("Failed to clear QQ Music account storage")
        }
        val cleared = QQMusicAuthBundle()
        _authFlow.value = cleared
        _cookieFlow.value = cleared.cookies
        _authHealthFlow.value = evaluateQQMusicAuthHealth(cleared)
        NPLogger.d("NERI-QQMusicAuthRepo", "Cleared QQ Music cookies")
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateQQMusicAuthHealth(_authFlow.value, now)
    }

    private fun loadAuthBundle(): QQMusicAuthBundle {
        val raw = runCatching {
            encryptedPrefs.getString(KEY_QQ_MUSIC_AUTH_BUNDLE, null).orEmpty()
        }.getOrElse { error ->
            NPLogger.w(
                "NERI-QQMusicAuthRepo",
                "Failed to read QQ Music secure prefs, recovering storage.",
                error
            )
            encryptedPrefs = AccountPreferencesOpener.fallback(context, QQ_MUSIC_AUTH_PREFS)
            runCatching { encryptedPrefs.getString(KEY_QQ_MUSIC_AUTH_BUNDLE, null).orEmpty() }
                .getOrDefault("")
        }
        if (raw.isBlank()) return QQMusicAuthBundle()
        return runCatching { decodeAuthBundle(raw) }.getOrElse { error ->
            NPLogger.w("NERI-QQMusicAuthRepo", "Failed to decode QQ Music auth bundle", error)
            QQMusicAuthBundle()
        }
    }

    private fun decodeAuthBundle(raw: String): QQMusicAuthBundle {
        val root = JSONObject(raw)
        val cookiesJson = root.optJSONObject("cookies") ?: return QQMusicAuthBundle()
        val cookies = linkedMapOf<String, String>()
        val keys = cookiesJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            cookies[key] = cookiesJson.optString(key, "")
        }
        return QQMusicAuthBundle(
            cookies = validateAndSanitizeQQMusicCookies(cookies).sanitizedCookies,
            savedAt = root.optLong("savedAt", 0L)
        )
    }

    private fun encodeAuthBundle(bundle: QQMusicAuthBundle): String {
        val cookiesJson = JSONObject()
        bundle.cookies.forEach { (key, value) -> cookiesJson.put(key, value) }
        return JSONObject()
            .put("cookies", cookiesJson)
            .put("savedAt", bundle.savedAt)
            .toString()
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences = AccountPreferencesOpener.open(
        context = context,
        name = QQ_MUSIC_AUTH_PREFS,
        create = { createEncryptedPrefs() },
        delete = { clearEncryptedStorage() },
        report = { event, error ->
            NPLogger.w("NERI-QQMusicAuthRepo", "QQ Music secure prefs $event", error)
        }
    )

    @SuppressLint("UseKtx")
    private fun writeAuthBundle(bundle: QQMusicAuthBundle?): Boolean = runCatching {
        if (!isAuthStoragePersistent) return false
        encryptedPrefs.edit().apply {
            remove(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING)
            if (bundle == null) remove(KEY_QQ_MUSIC_AUTH_BUNDLE)
            else putString(KEY_QQ_MUSIC_AUTH_BUNDLE, encodeAuthBundle(bundle))
        }.commit()
    }.onFailure { error ->
        NPLogger.w("NERI-QQMusicAuthRepo", "Failed to write QQ Music secure prefs", error)
    }.getOrDefault(false)

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            QQ_MUSIC_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearEncryptedStorage() {
        runCatching {
            context.deleteSharedPreferences(QQ_MUSIC_AUTH_PREFS)
        }.onFailure { error ->
            NPLogger.w(
                "NERI-QQMusicAuthRepo",
                "Failed to delete corrupted QQ Music secure prefs file.",
                error
            )
        }
    }
}
