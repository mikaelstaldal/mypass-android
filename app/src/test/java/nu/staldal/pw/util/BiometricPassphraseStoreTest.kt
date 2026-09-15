package nu.staldal.pw.util

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricPassphraseStoreTest {
    private class Preferences(var commitResult: Boolean = true) {
        val values = mutableMapOf<String, String>()
        private val pending = mutableMapOf<String, String?>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String?; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "commit", "apply" -> {
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    pending.clear()
                    if (method.name == "commit") commitResult else null
                }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Fake biometric preferences"
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "edit" -> editor
                "contains" -> values.containsKey(args!![0])
                "getString" -> values[args!![0]] ?: args[1]
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Fake biometric preferences"
                else -> error(method.name)
            }
        } as SharedPreferences
    }

    @Test
    fun persistenceFailureDoesNotEnableEnrollment() {
        val preferences = Preferences(commitResult = false)
        val store = BiometricPassphraseStore(preferences.prefs)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        assertFalse(store.store(cipher, "passphrase"))
        assertFalse(store.isEnabled())
    }

    @Test
    fun roundTripAndCorruptedCiphertext() {
        val preferences = Preferences()
        val store = BiometricPassphraseStore(preferences.prefs)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        assertTrue(store.store(encrypt, "passphrase"))
        fun decrypt() = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv))
        }
        assertEquals("passphrase", store.retrieve(decrypt()))
        preferences.values["ciphertext"] = Base64.getEncoder().encodeToString(ByteArray(32))
        assertNull(store.retrieve(decrypt()))
        assertFalse(store.isEnabled())
    }

    @Test
    fun unavailableCipherDoesNotDestroyEnrollment() {
        val preferences = Preferences()
        val store = BiometricPassphraseStore(preferences.prefs)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        assertTrue(store.store(encrypt, "passphrase"))
        assertNull(store.retrieve(Cipher.getInstance("AES/GCM/NoPadding")))
        assertTrue(store.isEnabled())
    }

    @Test
    fun malformedIvDoesNotCrash() {
        val preferences = Preferences()
        val store = BiometricPassphraseStore(preferences.prefs)
        preferences.values["iv"] = "!!!"
        preferences.values["ciphertext"] = "unused"
        assertNull(store.decryptCipher())
        assertFalse(store.isEnabled())
    }
}
