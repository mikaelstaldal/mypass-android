package nu.staldal.pw.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optionally keeps the master passphrase behind the device's biometric lock,
 * so the vault can be opened with a fingerprint instead of by typing a long
 * passphrase on a phone keyboard.
 *
 * The passphrase is wrapped with an AES-GCM key held in the Android Keystore
 * (hardware-backed where the device has a TEE or StrongBox) that is marked
 * `setUserAuthenticationRequired`, so the key cannot be used without a fresh
 * biometric authentication, and `setInvalidatedByBiometricEnrollment`, so
 * enrolling a new fingerprint destroys it rather than granting the new finger
 * access. Only the wrapped bytes are stored, in ordinary preferences.
 *
 * This weakens the vault to the device's biometric gate for as long as it is
 * enabled — a deliberate trade, off by default, and undone by [clear].
 */
class BiometricPassphraseStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("pw-biometric", Context.MODE_PRIVATE)

    /** Whether a passphrase has been wrapped and is available to unwrap. */
    fun isEnabled(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    /**
     * A cipher for wrapping the passphrase, or `null` if the device has no
     * usable keystore. Pass it to `BiometricPrompt.authenticate`, then hand the
     * authenticated cipher to [store].
     */
    fun encryptCipher(): Cipher? = try {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, orCreateKey()) }
    } catch (e: Exception) {
        null
    }

    /**
     * A cipher for unwrapping, or `null` when there is nothing stored or the
     * key was invalidated by a biometric enrollment — in which case the stale
     * ciphertext is dropped, since it can never be read again.
     */
    fun decryptCipher(): Cipher? {
        val iv = prefs.getString(KEY_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return null
        val key = existingKey() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Wrap and store the passphrase under an already-authenticated cipher. */
    fun store(cipher: Cipher, passphrase: String): Boolean = try {
        val ciphertext = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        // commit, not apply: the caller reports success to the user, and
        // "enabled" must not mean "queued".
        prefs.edit(commit = true) {
            putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        true
    } catch (e: Exception) {
        false
    }

    /** Unwrap the passphrase under an already-authenticated cipher. */
    fun retrieve(cipher: Cipher): String? = try {
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Forget the wrapped passphrase and destroy the key that could read it. */
    fun clear() {
        prefs.edit { remove(KEY_CIPHERTEXT); remove(KEY_IV) }
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // Nothing to delete, or a keystore that will not talk to us.
            // Either way the ciphertext is gone and cannot be read back.
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = try {
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey
    } catch (e: Exception) {
        null
    }

    private fun orCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Every use needs its own authentication, by a strong
                    // biometric only — not the device credential.
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pw-passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
    }
}
