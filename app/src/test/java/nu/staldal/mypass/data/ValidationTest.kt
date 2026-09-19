package nu.staldal.mypass.data

import nu.staldal.mypass.vault.PasswordEntry
import nu.staldal.mypass.vault.Secret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ValidationTest {
    @Test
    fun displayReplacesSpoofingCharactersAndBoundsUntrustedText() {
        assertEquals("a�b�c�d", Validation.displayText("a\u202Eb\u0000c\u200Bd"))
        assertEquals("a��", Validation.displayText("a\uFEFF\u2060"))
        assertEquals("😀".repeat(256), Validation.displayText("😀".repeat(256)))
        assertEquals("😀".repeat(256) + "…", Validation.displayText("😀".repeat(257)))
        assertEquals("x".repeat(256), Validation.displayText("x".repeat(256)))
        assertEquals("😀", Validation.displayText("😀"))
        assertEquals("x".repeat(256) + "…", Validation.displayText("x".repeat(257)))
        assertEquals("invalid entry name: contains control characters",
            MyPassException.InvalidInput("entry name", "contains control characters").message)
        assertEquals("no entry 'bad�name' in the vault", MyPassException.NotFound("bad\u202Ename").message)
    }

    @Test
    fun vaultErrorsUseSafePositionsInsteadOfUntrustedMetadata() {
        val bad = PasswordEntry("bad\u202Ename", "user", Secret("secret"))
        val error = assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateEntries(listOf(bad))
        }
        org.junit.Assert.assertTrue(error.message!!.contains("entry 1 of 1"))
        org.junit.Assert.assertFalse(error.message!!.contains(bad.name))
        org.junit.Assert.assertFalse(error.message!!.contains("secret"))
    }

    @Test
    fun acceptsOrdinaryNames() {
        Validation.validateName("github.com")
        Validation.validateName("work github")
        Validation.validateName("bücher.example")
        Validation.validateUsername("")
        Validation.validateUsername("alice@example.com")
    }

    @Test
    fun rejectsEmptyName() {
        val e = assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateName("")
        }
        assertEquals("entry name", e.what)
    }

    @Test
    fun rejectsControlCharacters() {
        assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateName("git\nhub")
        }
        // U+009F is Unicode category Cc even though it is not an ASCII control
        // code; Character.isISOControl covers exactly that category, which is
        // the same set Rust's char::is_control tests.
        assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateName("git\u009Fhub")
        }
    }

    @Test
    fun rejectsBidirectionalAndZeroWidthCharacters() {
        // RLO, LRI, ZWSP, ZWJ, BOM, Arabic Letter Mark: characters that can
        // make a name render as something other than what it is.
        val spoofs = listOf("\u202E", "\u2066", "\u200B", "\u200D", "\uFEFF", "\u061C")
        for (spoof in spoofs) {
            assertThrows(MyPassException.InvalidInput::class.java) {
                Validation.validateName("github" + spoof + ".com")
            }
        }
    }

    @Test
    fun rejectsOverlongText() {
        Validation.validateName("a".repeat(Validation.MAX_NAME_LEN))
        assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateName("a".repeat(Validation.MAX_NAME_LEN + 1))
        }
        // Counted in code points, not UTF-16 units: an astral character is one,
        // so a name of MAX_NAME_LEN of them is still accepted.
        Validation.validateName("🔑".repeat(Validation.MAX_NAME_LEN))
    }

    @Test
    fun realmNeedsAUrl() {
        Validation.validateSite("github.com", "Admin Area")
        Validation.validateSite("github.com", null)
        Validation.validateSite(null, null)
        val e = assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateSite(null, "Admin Area")
        }
        assertEquals("realm", e.what)
    }

    @Test
    fun emptyUrlOrRealmIsRejectedRatherThanStored() {
        assertThrows(MyPassException.InvalidInput::class.java) { Validation.validateUrl("") }
        assertThrows(MyPassException.InvalidInput::class.java) { Validation.validateRealm("") }
    }

    @Test
    fun validatesWholeEntry() {
        Validation.validateEntry(
            PasswordEntry("a", "u", Secret("p"), url = "github.com", realm = "Admin")
        )
        assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateEntry(PasswordEntry("", "u", Secret("p")))
        }
        assertThrows(MyPassException.InvalidInput::class.java) {
            Validation.validateEntry(PasswordEntry("a", "u", Secret("p"), realm = "Admin"))
        }
    }
}
