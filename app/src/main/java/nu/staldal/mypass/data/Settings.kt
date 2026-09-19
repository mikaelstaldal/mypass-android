package nu.staldal.mypass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import nu.staldal.mypass.crypto.ScryptFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mypass-settings")

/** Everything the user can configure. Nothing secret is stored here. */
data class SettingsState(
    /**
     * Minutes of inactivity after which the vault relocks, counted from the
     * last time it was read or written. 0 means "not on a timer" — the vault
     * then stays open until you lock it or the process dies. This is the
     * counterpart of the desktop browser host's `cache_minutes`.
     */
    val autoLockMinutes: Int = DEFAULT_AUTO_LOCK_MINUTES,
    /**
     * Also lock the moment the app leaves the foreground. Off by default,
     * because the autofill service shares this process: with it on, every
     * autofill in a browser needs the passphrase again.
     */
    val lockOnBackground: Boolean = false,
    /**
     * Seconds a copied password stays on the clipboard before MyPass clears it.
     * 0 leaves the clipboard untouched.
     */
    val clipboardClearSeconds: Int = DEFAULT_CLIPBOARD_CLEAR_SECONDS,
    val passwordLength: Int = PasswordGenerator.DEFAULT_LENGTH,
    val passwordCharset: String = PasswordGenerator.DEFAULT_CHARSET,
    /**
     * log2(N) for writes. Prefer desktop cost 17, lowered automatically when
     * this device cannot accept it, at the cost of a cheaper offline attack.
     */
    val scryptLogN: Int = ScryptDefaults.LOG_N,
    /**
     * Explicit package/certificate enrollments, stored outside the shared vault.
     * Legacy package-only settings are intentionally never migrated.
     */
    val browserCertificatePins: String = "",
    /** Browser signing identities the user explicitly declined to trust. */
    val rejectedBrowserCertificates: String = "",
    /**
     * Record why recent autofill requests produced no offer. Off by default;
     * the records live in memory and are emitted to Logcat, hold no field
     * contents and no entry names, and are dropped from memory when this is
     * switched off. Only the preference itself is persisted by MyPass.
     */
    val autofillDiagnostics: Boolean = false,
) {
    companion object {
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
        const val DEFAULT_CLIPBOARD_CLEAR_SECONDS = 20
    }
}

object ScryptDefaults {
    val LOG_N: Int get() = MAX_LOG_N
    const val MIN_LOG_N = 10
    val MAX_LOG_N: Int get() = ScryptFormat.maxSupportedLogN()
}

class Settings(context: Context) {

    private val store = context.applicationContext.dataStore

    val state: Flow<SettingsState> = store.data.map { prefs ->
        SettingsState(
            autoLockMinutes = prefs[AUTO_LOCK_MINUTES]
                ?: SettingsState.DEFAULT_AUTO_LOCK_MINUTES,
            lockOnBackground = prefs[LOCK_ON_BACKGROUND] ?: false,
            clipboardClearSeconds = prefs[CLIPBOARD_CLEAR_SECONDS]
                ?: SettingsState.DEFAULT_CLIPBOARD_CLEAR_SECONDS,
            passwordLength = prefs[PASSWORD_LENGTH] ?: PasswordGenerator.DEFAULT_LENGTH,
            passwordCharset = prefs[PASSWORD_CHARSET] ?: PasswordGenerator.DEFAULT_CHARSET,
            scryptLogN = (prefs[SCRYPT_LOG_N] ?: ScryptDefaults.LOG_N)
                .coerceIn(ScryptDefaults.MIN_LOG_N, ScryptDefaults.MAX_LOG_N),
            browserCertificatePins = prefs[BROWSER_CERTIFICATE_PINS] ?: "",
            rejectedBrowserCertificates = prefs[REJECTED_BROWSER_CERTIFICATES] ?: "",
            autofillDiagnostics = prefs[AUTOFILL_DIAGNOSTICS] ?: false,
        )
    }

    suspend fun setAutoLockMinutes(value: Int) = put(AUTO_LOCK_MINUTES, value.coerceAtLeast(0))

    suspend fun setLockOnBackground(value: Boolean) = put(LOCK_ON_BACKGROUND, value)

    suspend fun setClipboardClearSeconds(value: Int) =
        put(CLIPBOARD_CLEAR_SECONDS, value.coerceAtLeast(0))

    suspend fun setPasswordLength(value: Int) =
        put(PASSWORD_LENGTH, value.coerceIn(1, PasswordGenerator.MAX_LENGTH))

    suspend fun setPasswordCharset(value: String) = put(PASSWORD_CHARSET, value)

    suspend fun setScryptLogN(value: Int) =
        put(SCRYPT_LOG_N, value.coerceIn(ScryptDefaults.MIN_LOG_N, ScryptDefaults.MAX_LOG_N))

    suspend fun setBrowserCertificatePins(value: String) {
        store.edit {
            it[BROWSER_CERTIFICATE_PINS] = value
            it.remove(stringPreferencesKey("extra_browser_packages"))
        }
    }

    suspend fun setRejectedBrowserCertificates(value: String) =
        put(REJECTED_BROWSER_CERTIFICATES, value)

    suspend fun setAutofillDiagnostics(value: Boolean) = put(AUTOFILL_DIAGNOSTICS, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private companion object {
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
        val LOCK_ON_BACKGROUND = booleanPreferencesKey("lock_on_background")
        val CLIPBOARD_CLEAR_SECONDS = intPreferencesKey("clipboard_clear_seconds")
        val PASSWORD_LENGTH = intPreferencesKey("password_length")
        val PASSWORD_CHARSET = stringPreferencesKey("password_charset")
        val SCRYPT_LOG_N = intPreferencesKey("scrypt_log_n")
        val BROWSER_CERTIFICATE_PINS = stringPreferencesKey("browser_certificate_pins_v1")
        val REJECTED_BROWSER_CERTIFICATES = stringPreferencesKey("rejected_browser_certificates_v1")
        val AUTOFILL_DIAGNOSTICS = booleanPreferencesKey("autofill_diagnostics")
    }
}
