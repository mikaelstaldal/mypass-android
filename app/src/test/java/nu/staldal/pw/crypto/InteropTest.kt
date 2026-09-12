package nu.staldal.pw.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Known-answer interoperability with Tarsnap's `scrypt` tool and with desktop
 * `pw`.
 *
 * `known_answer.scrypt` is the desktop repository's own fixture, generated
 * once by scrypt 1.3.2 with
 * `scrypt enc --logN 12 -r 8 -p 1 --passphrase file:<passphrase-file>`. If this
 * test fails, this app can no longer read vaults the `scrypt` tool writes —
 * which is the whole compatibility guarantee.
 */
class InteropTest {

    private val passphrase = "correct horse battery staple".toByteArray()
    private val plaintext = """{"version":1,"entries":[]}""".toByteArray()

    private fun fixture(): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("known_answer.scrypt")
        assertNotNull("known_answer.scrypt is missing from the test resources", stream)
        return stream!!.use { it.readBytes() }
    }

    @Test
    fun knownAnswerDecrypts() {
        val data = fixture()
        assertEquals(ScryptFormat.OVERHEAD + plaintext.size, data.size)
        assertArrayEquals(plaintext, ScryptFormat.decrypt(data, passphrase))
    }

    @Test
    fun knownAnswerRejectsWrongPassphrase() {
        assertThrows(ScryptFormatException.WrongPassphrase::class.java) {
            ScryptFormat.decrypt(fixture(), "wrong".toByteArray())
        }
    }

    @Test
    fun ourOutputHasTheSameShapeAsTheFixture() {
        // Same parameters as the fixture, so the header bytes that do not
        // depend on the salt must match byte for byte.
        val ours = ScryptFormat.encrypt(
            plaintext,
            passphrase,
            ScryptFormat.Params(logN = 12, r = 8, p = 1),
        )
        val theirs = fixture()
        assertEquals(theirs.size, ours.size)
        assertArrayEquals(theirs.copyOfRange(0, 16), ours.copyOfRange(0, 16))
    }
}
