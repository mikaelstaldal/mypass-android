package nu.staldal.mypass.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.SCrypt

/**
 * The scrypt encrypted-data format, version 0.
 *
 * Byte-compatible with the `scrypt` command-line tool (Tarsnap's `scryptenc`)
 * and with the desktop `MyPass`: files written by [encrypt] can be decrypted with
 * `scrypt dec`, and files written by `scrypt enc` can be read by [decrypt].
 * This object does no I/O and knows nothing about the vault contents.
 *
 * Layout:
 * ```text
 * offset  size  field
 *  0       6    magic "scrypt"
 *  6       1    version = 0
 *  7       1    log2(N)
 *  8       4    r (big-endian u32)
 * 12       4    p (big-endian u32)
 * 16      32    salt
 * 48      16    SHA-256(bytes 0..48), first 16 bytes   (file-type checksum)
 * 64      32    HMAC-SHA256(key_hmac, bytes 0..64)     (passphrase check)
 * 96       n    AES-256-CTR(key_enc, nonce=0) ciphertext
 * 96+n    32    HMAC-SHA256(key_hmac, bytes 0..96+n)   (integrity)
 * ```
 * where `dk = scrypt(passphrase, salt, N, r, p)` (64 bytes),
 * `key_enc = dk[0..32]` and `key_hmac = dk[32..64]`.
 */
object ScryptFormat {

    private val MAGIC = "scrypt".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Int = 0
    private const val SALT_LEN = 32

    const val HEADER_LEN = 96
    const val TRAILER_LEN = 32

    /** Total size added to the plaintext by the format. */
    const val OVERHEAD = HEADER_LEN + TRAILER_LEN

    /**
     * Cap on the memory the KDF may require when decrypting, so a corrupt or
     * malicious header cannot demand an enormous allocation.
     */
    private const val MAX_KDF_MEMORY = 160L shl 20 // includes scrypt scratch arrays
    private const val MAX_LOG_N = 22
    private const val MAX_WORK = 1L shl 20 // N * r * p; desktop defaults

    /** scrypt KDF cost parameters as stored in the file header. */
    data class Params(val logN: Int, val r: Int, val p: Int) {
        companion object {
            /**
             * Fixed write-side defaults, the same as desktop `MyPass`:
             * `N = 2^17, r = 8, p = 1` (~128 MiB).
             */
            val DEFAULT = Params(logN = 17, r = 8, p = 1)
        }
    }

    internal fun validate(
        logN: Int, r: Long, p: Long,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
    ) {
        if (logN <= 0 || r <= 0L || p <= 0L || r > Int.MAX_VALUE || p > Int.MAX_VALUE || r * p >= (1L shl 30) ||
            (r == 1L && logN >= 16)
        ) {
            throw ScryptFormatException.InvalidParams(logN, r, p)
        }
        // Bound logN before shifting/multiplying untrusted unsigned header values.
        if (logN > MAX_LOG_N) throw ScryptFormatException.ParamsTooLarge(logN, r)
        val n = 1L shl logN
        val memory = 128L * r * (n + p + 2)
        val memoryLimit = minOf(MAX_KDF_MEMORY, maxHeapBytes / 2)
        if (memory > memoryLimit || n * r * p > MAX_WORK) {
            throw ScryptFormatException.ParamsTooLarge(logN, r)
        }
    }

    /** Highest write cost (r=8, p=1) accepted on this device, without running a KDF. */
    fun maxSupportedLogN(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
        (Params.DEFAULT.logN downTo 1).firstOrNull { logN ->
            try {
                validate(logN, 8, 1, maxHeapBytes)
                true
            } catch (_: ScryptFormatException.ParamsTooLarge) {
                false
            }
        } ?: 0

    /** Caller owns the derived key and must wipe it. */
    private fun deriveKeys(passphrase: ByteArray, salt: ByteArray, params: Params): ByteArray =
        try {
            SCrypt.generate(passphrase, salt, 1 shl params.logN, params.r, params.p, 64)
        } catch (_: IllegalArgumentException) {
            throw ScryptFormatException.InvalidParams(params.logN, params.r.toLong(), params.p.toLong())
        }

    private fun hmac(keyHmac: ByteArray, data: ByteArray, length: Int = data.size): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyHmac, "HmacSHA256"))
        mac.update(data, 0, length)
        return mac.doFinal()
    }

    private fun sha256(data: ByteArray, length: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, 0, length)
        return digest.digest()
    }

    /**
     * AES-256-CTR with an all-zero 128-bit big-endian counter, applied in
     * place over `data[from until to]`. The keystream is its own inverse, so
     * this both encrypts and decrypts.
     */
    private fun applyKeystream(keyEnc: ByteArray, data: ByteArray, from: Int, to: Int) {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyEnc, "AES"),
            IvParameterSpec(ByteArray(16)),
        )
        cipher.doFinal(data, from, to - from, data, from)
    }

    private fun putInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun getUInt(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    /** Encrypt [plaintext] into a self-contained scrypt-format file image. */
    fun encrypt(plaintext: ByteArray, passphrase: ByteArray, params: Params): ByteArray {
        validate(params.logN, params.r.toLong(), params.p.toLong())
        val salt = ByteArray(SALT_LEN).also { Random.secureRandom.nextBytes(it) }
        return encryptWithSalt(plaintext, passphrase, params, salt)
    }

    internal fun encryptWithSalt(
        plaintext: ByteArray,
        passphrase: ByteArray,
        params: Params,
        salt: ByteArray,
    ): ByteArray {
        validate(params.logN, params.r.toLong(), params.p.toLong())
        val out = ByteArray(OVERHEAD + plaintext.size)
        MAGIC.copyInto(out)
        out[6] = VERSION.toByte()
        out[7] = params.logN.toByte()
        putInt(out, 8, params.r)
        putInt(out, 12, params.p)
        salt.copyInto(out, 16)
        sha256(out, 48).copyInto(out, 48, 0, 16)

        val dk = deriveKeys(passphrase, salt, params)
        try {
            val keyEnc = dk.copyOfRange(0, 32)
            val keyHmac = dk.copyOfRange(32, 64)
            try {
                hmac(keyHmac, out, 64).copyInto(out, 64)

                // Copy the plaintext in and encrypt in place, so no extra
                // plaintext copy is left behind for the GC to scatter.
                plaintext.copyInto(out, HEADER_LEN)
                applyKeystream(keyEnc, out, HEADER_LEN, HEADER_LEN + plaintext.size)

                hmac(keyHmac, out, HEADER_LEN + plaintext.size)
                    .copyInto(out, HEADER_LEN + plaintext.size)
            } finally {
                keyEnc.fill(0)
                keyHmac.fill(0)
            }
        } finally {
            dk.fill(0)
        }
        return out
    }

    /**
     * Decrypt a scrypt-format file image. The exceptions distinguish "wrong
     * file type" ([ScryptFormatException.NotScryptFormat]), "wrong passphrase"
     * ([ScryptFormatException.WrongPassphrase]) and "damaged file"
     * ([ScryptFormatException.Corrupt]).
     *
     * The caller owns the returned plaintext and should wipe it when done.
     */
    fun decrypt(data: ByteArray, passphrase: ByteArray): ByteArray {
        if (data.size < MAGIC.size || !MAGIC.contentEquals(data.copyOfRange(0, MAGIC.size))) {
            throw ScryptFormatException.NotScryptFormat()
        }
        // The version byte follows the magic, so a file that is *exactly* the
        // magic is truncated rather than unversioned. Checking this before
        // reading it keeps every failure inside the exception hierarchy the
        // layers above map; without it a six-byte file threw
        // ArrayIndexOutOfBoundsException straight past them.
        if (data.size <= MAGIC.size) throw ScryptFormatException.Truncated()
        val version = data[6].toInt() and 0xFF
        if (version != VERSION) throw ScryptFormatException.UnsupportedVersion(version)
        if (data.size < OVERHEAD) throw ScryptFormatException.Truncated()

        val logN = data[7].toInt() and 0xFF
        val r = getUInt(data, 8)
        val p = getUInt(data, 12)
        validate(logN, r, p)
        val params = Params(logN, r.toInt(), p.toInt())

        if (!MessageDigest.isEqual(
                sha256(data, 48).copyOfRange(0, 16),
                data.copyOfRange(48, 64),
            )
        ) {
            throw ScryptFormatException.NotScryptFormat()
        }

        val salt = data.copyOfRange(16, 48)
        val dk = deriveKeys(passphrase, salt, params)
        try {
            val keyEnc = dk.copyOfRange(0, 32)
            val keyHmac = dk.copyOfRange(32, 64)
            try {
                if (!MessageDigest.isEqual(
                        hmac(keyHmac, data, 64),
                        data.copyOfRange(64, HEADER_LEN),
                    )
                ) {
                    throw ScryptFormatException.WrongPassphrase()
                }

                val bodyEnd = data.size - TRAILER_LEN
                if (!MessageDigest.isEqual(
                        hmac(keyHmac, data, bodyEnd),
                        data.copyOfRange(bodyEnd, data.size),
                    )
                ) {
                    throw ScryptFormatException.Corrupt()
                }

                val plaintext = data.copyOfRange(HEADER_LEN, bodyEnd)
                applyKeystream(keyEnc, plaintext, 0, plaintext.size)
                return plaintext
            } finally {
                keyEnc.fill(0)
                keyHmac.fill(0)
            }
        } finally {
            dk.fill(0)
        }
    }
}
