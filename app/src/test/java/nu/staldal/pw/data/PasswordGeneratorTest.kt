package nu.staldal.pw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {

    @Test
    fun generatesRequestedLengthFromRequestedCharset() {
        val password = PasswordGenerator.generate(32, "ab").expose()
        assertEquals(32, password.length)
        assertTrue(password.all { it == 'a' || it == 'b' })
    }

    @Test
    fun defaultsAreTheSameAsDesktopPw() {
        assertEquals(
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-",
            PasswordGenerator.DEFAULT_CHARSET,
        )
        assertEquals(16, PasswordGenerator.DEFAULT_LENGTH)
        assertEquals(1024, PasswordGenerator.MAX_LENGTH)
    }

    @Test
    fun successivePasswordsDiffer() {
        val a = PasswordGenerator.generate(32, PasswordGenerator.DEFAULT_CHARSET).expose()
        val b = PasswordGenerator.generate(32, PasswordGenerator.DEFAULT_CHARSET).expose()
        assertNotEquals(a, b)
    }

    @Test
    fun rejectsImpossibleRequests() {
        assertThrows(PwException.InvalidInput::class.java) {
            PasswordGenerator.generate(0, "ab")
        }
        assertThrows(PwException.InvalidInput::class.java) {
            PasswordGenerator.generate(PasswordGenerator.MAX_LENGTH + 1, "ab")
        }
        assertThrows(PwException.InvalidInput::class.java) {
            PasswordGenerator.generate(8, "a")
        }
        // Fewer than two *distinct* characters is just as unusable.
        assertThrows(PwException.InvalidInput::class.java) {
            PasswordGenerator.generate(8, "aaaa")
        }
    }

    @Test
    fun charsetIsCountedInCodePointsSoAstralCharactersAreNotSplit() {
        val password = PasswordGenerator.generate(10, "🔑🔒").expose()
        assertEquals(10, password.codePointCount(0, password.length))
        assertEquals(20, password.length)
    }

    @Test
    fun everyCharacterOfTheCharsetIsReachable() {
        // Not a statistical test — a sanity check that the index range covers
        // the whole charset rather than dropping either end of it.
        val seen = HashSet<Char>()
        repeat(20) {
            PasswordGenerator.generate(200, "abc").expose().forEach(seen::add)
        }
        assertEquals(setOf('a', 'b', 'c'), seen)
    }
}
