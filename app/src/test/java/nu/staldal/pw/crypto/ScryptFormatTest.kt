package nu.staldal.pw.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScryptFormatTest {

    private val passphrase = "correct horse battery staple".toByteArray()
    private val plaintext = """{"version":1,"entries":[]}""".toByteArray()

    // Small parameters so the tests stay fast; the production defaults are
    // exercised by [defaultParamsAreValid].
    private val testParams = ScryptFormat.Params(logN = 12, r = 8, p = 1)

    private fun encrypted() = ScryptFormat.encrypt(plaintext, passphrase, testParams)

    @Test
    fun roundTrip() {
        val data = encrypted()
        assertEquals(ScryptFormat.OVERHEAD + plaintext.size, data.size)
        assertArrayEquals(plaintext, ScryptFormat.decrypt(data, passphrase))
    }

    @Test
    fun roundTripEmptyPlaintext() {
        val data = ScryptFormat.encrypt(ByteArray(0), passphrase, testParams)
        assertEquals(ScryptFormat.OVERHEAD, data.size)
        assertArrayEquals(ByteArray(0), ScryptFormat.decrypt(data, passphrase))
    }

    @Test
    fun saltsDifferBetweenEncryptions() {
        val a = encrypted()
        val b = encrypted()
        assertNotEquals(
            a.copyOfRange(16, 48).toList(),
            b.copyOfRange(16, 48).toList(),
        )
        assertNotEquals(
            a.copyOfRange(ScryptFormat.HEADER_LEN, a.size).toList(),
            b.copyOfRange(ScryptFormat.HEADER_LEN, b.size).toList(),
        )
    }

    @Test
    fun defaultParamsAreValid() {
        // Not a round trip: N=2^17 is deliberately slow. This only checks that
        // the write-side defaults pass validation.
        val params = ScryptFormat.Params.DEFAULT
        assertEquals(17, params.logN)
        assertEquals(8, params.r)
        assertEquals(1, params.p)
    }

    @Test
    fun wrongPassphrase() {
        assertThrows(ScryptFormatException.WrongPassphrase::class.java) {
            ScryptFormat.decrypt(encrypted(), "wrong".toByteArray())
        }
    }

    @Test
    fun badMagic() {
        val data = encrypted()
        data[0] = (data[0].toInt() xor 0x01).toByte()
        assertThrows(ScryptFormatException.NotScryptFormat::class.java) {
            ScryptFormat.decrypt(data, passphrase)
        }
    }

    @Test
    fun unsupportedVersion() {
        val data = encrypted()
        data[6] = 1
        val e = assertThrows(ScryptFormatException.UnsupportedVersion::class.java) {
            ScryptFormat.decrypt(data, passphrase)
        }
        assertEquals(1, e.version)
    }

    @Test
    fun saltBitFlipFailsChecksum() {
        val data = encrypted()
        data[20] = (data[20].toInt() xor 0x01).toByte()
        assertThrows(ScryptFormatException.NotScryptFormat::class.java) {
            ScryptFormat.decrypt(data, passphrase)
        }
    }

    @Test
    fun headerMacBitFlip() {
        val data = encrypted()
        data[70] = (data[70].toInt() xor 0x01).toByte()
        assertThrows(ScryptFormatException.WrongPassphrase::class.java) {
            ScryptFormat.decrypt(data, passphrase)
        }
    }

    @Test
    fun bodyBitFlip() {
        val data = encrypted()
        data[ScryptFormat.HEADER_LEN] =
            (data[ScryptFormat.HEADER_LEN].toInt() xor 0x01).toByte()
        assertThrows(ScryptFormatException.Corrupt::class.java) {
            ScryptFormat.decrypt(data, passphrase)
        }
    }

    @Test
    fun trailerBitFlip() {
        val data = encrypted()
        val last = data.size - 1
        data[last] = (data[last].toInt() xor 0x01).toByte()
        assertThrows(ScryptFormatException.Corrupt::class.java) {
            ScryptFormat.decrypt(data, passphrase)
        }
    }

    @Test
    fun fileThatIsOnlyTheMagicIsTruncated() {
        // Exactly the six magic bytes: past the magic check, but there is no
        // version byte to read. Must stay inside the exception hierarchy.
        assertThrows(ScryptFormatException.Truncated::class.java) {
            ScryptFormat.decrypt("scrypt".toByteArray(), passphrase)
        }
        // ...and so must every shorter prefix of it.
        for (length in 0..5) {
            assertThrows(ScryptFormatException.NotScryptFormat::class.java) {
                ScryptFormat.decrypt("scrypt".toByteArray().copyOfRange(0, length), passphrase)
            }
        }
    }

    @Test
    fun truncatedFile() {
        val data = encrypted()
        assertThrows(ScryptFormatException.Truncated::class.java) {
            ScryptFormat.decrypt(data.copyOfRange(0, ScryptFormat.OVERHEAD - 1), passphrase)
        }
        // Cut inside the body: the trailer MAC no longer matches.
        assertThrows(ScryptFormatException.Corrupt::class.java) {
            ScryptFormat.decrypt(data.copyOfRange(0, data.size - 1), passphrase)
        }
    }

    @Test
    fun oversizedParamsRejectedBeforeKdf() {
        val data = encrypted()
        data[7] = 30 // log2(N) = 30 would need 8 GiB at r=8
        val e = assertThrows(ScryptFormatException.ParamsTooLarge::class.java) {
            ScryptFormat.decrypt(data, passphrase)
        }
        assertEquals(30, e.logN)
        assertEquals(8L, e.r)
    }

    @Test
    fun zeroParamsRejected() {
        for (params in listOf(
            ScryptFormat.Params(0, 8, 1),
            ScryptFormat.Params(12, 0, 1),
            ScryptFormat.Params(12, 8, 0),
        )) {
            assertThrows(ScryptFormatException.InvalidParams::class.java) {
                ScryptFormat.encrypt(plaintext, passphrase, params)
            }
        }
    }

    @Test
    fun rTimesPAtOrAboveTwoToThirtyRejected() {
        // The scrypt specification requires r * p < 2^30.
        assertThrows(ScryptFormatException.InvalidParams::class.java) {
            ScryptFormat.encrypt(plaintext, passphrase, ScryptFormat.Params(12, 1 shl 20, 1024))
        }
    }

    @Test
    fun headerIsExactlyWhatTheFormatSpecifies() {
        val data = encrypted()
        assertArrayEquals("scrypt".toByteArray(), data.copyOfRange(0, 6))
        assertEquals(0, data[6].toInt())
        assertEquals(12, data[7].toInt())
        assertArrayEquals(byteArrayOf(0, 0, 0, 8), data.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), data.copyOfRange(12, 16))
        assertTrue(data.size > ScryptFormat.HEADER_LEN)
    }
}
