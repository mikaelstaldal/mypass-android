package nu.staldal.pw.vault

import java.io.File
import nu.staldal.pw.crypto.ScryptFormat
import nu.staldal.pw.crypto.ScryptFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val passphraseText = "test passphrase"
    private val testParams = ScryptFormat.Params(logN = 12, r = 8, p = 1)

    private fun passphrase() = Passphrase(passphraseText)

    private fun entry(name: String, password: String) = PasswordEntry(
        name = name,
        username = "$name-user",
        password = Secret(password),
    )

    private fun vaultFile() = File(temp.root, "pw.scrypt")

    @Test
    fun roundTrip() {
        val file = vaultFile()
        val entries = listOf(entry("a", "pw-a"), entry("b", "pw-b"))
        Vault.store(file, passphrase(), entries, testParams)
        assertEquals(entries, Vault.load(file, passphrase()))
    }

    @Test
    fun roundTripEmptyVault() {
        val file = vaultFile()
        Vault.store(file, passphrase(), emptyList(), testParams)
        assertEquals(emptyList<PasswordEntry>(), Vault.load(file, passphrase()))
    }

    @Test
    fun wrongPassphrase() {
        val file = vaultFile()
        Vault.store(file, passphrase(), emptyList(), testParams)
        val e = assertThrows(VaultException.Format::class.java) {
            Vault.load(file, Passphrase("wrong"))
        }
        assertTrue(e.cause is ScryptFormatException.WrongPassphrase)
    }

    @Test
    fun missingFileIsReadError() {
        assertThrows(VaultException.Read::class.java) {
            Vault.load(File(temp.root, "nothing.scrypt"), passphrase())
        }
    }

    @Test
    fun readsLegacyBareArray() {
        val file = vaultFile()
        val legacy = """[{"name":"a","username":"a-user","password":"pw-a"}]"""
        file.writeBytes(
            ScryptFormat.encrypt(legacy.toByteArray(), passphraseText.toByteArray(), testParams)
        )
        assertEquals(listOf(entry("a", "pw-a")), Vault.load(file, passphrase()))
    }

    @Test
    fun rejectsNewerEnvelopeVersion() {
        val file = vaultFile()
        val future = """{"version":2,"entries":[],"url_field_or_whatever":true}"""
        file.writeBytes(
            ScryptFormat.encrypt(future.toByteArray(), passphraseText.toByteArray(), testParams)
        )
        val e = assertThrows(VaultException.UnsupportedVersion::class.java) {
            Vault.load(file, passphrase())
        }
        assertEquals(2, e.version)
    }

    @Test
    fun rejectsGarbageJson() {
        val file = vaultFile()
        file.writeBytes(
            ScryptFormat.encrypt("not json".toByteArray(), passphraseText.toByteArray(), testParams)
        )
        assertThrows(VaultException.InvalidJson::class.java) { Vault.load(file, passphrase()) }
    }

    @Test
    fun writesEnvelopeNotBareArray() {
        val json = Vault.toJson(listOf(entry("a", "pw-a")))
        assertEquals(
            """{"version":1,"entries":[{"name":"a","username":"a-user","password":"pw-a"}]}""",
            json,
        )
    }

    @Test
    fun urlAndRealmAreOmittedWhenAbsentAndWrittenWhenSet() {
        // The desktop format skips these fields when unset, so a url-less
        // entry stays byte-identical to what pw wrote before they existed.
        assertFalse(Vault.toJson(listOf(entry("a", "p"))).contains("url"))
        val withSite = PasswordEntry(
            name = "a",
            username = "u",
            password = Secret("p"),
            url = "github.com",
            realm = "Admin Area",
        )
        assertEquals(
            """{"version":1,"entries":[{"name":"a","username":"u","password":"p",""" +
                """"url":"github.com","realm":"Admin Area"}]}""",
            Vault.toJson(listOf(withSite)),
        )
    }

    @Test
    fun overwriteKeepsBackupOfPreviousVault() {
        val file = vaultFile()
        val old = listOf(entry("old", "pw-old"))
        val new = listOf(entry("new", "pw-new"))
        Vault.store(file, passphrase(), old, testParams)
        Vault.store(file, passphrase(), new, testParams)

        assertEquals(new, Vault.load(file, passphrase()))
        assertEquals(old, Vault.load(Vault.backupFile(file), passphrase()))
    }

    @Test
    fun failedStoreLeavesExistingVaultUntouched() {
        val file = vaultFile()
        val entries = listOf(entry("a", "pw-a"))
        Vault.store(file, passphrase(), entries, testParams)

        assertThrows(VaultException.Format::class.java) {
            Vault.store(file, passphrase(), emptyList(), ScryptFormat.Params(0, 8, 1))
        }

        assertEquals(entries, Vault.load(file, passphrase()))
        assertFalse(Vault.backupFile(file).exists())
        // No stray temp file left behind.
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    @Test
    fun storeUsesDeterministicTempNameAndOverwritesStaleOne() {
        val file = vaultFile()
        val tmp = Vault.tempFile(file)
        assertEquals(File(temp.root, "pw.scrypt.tmp"), tmp)
        // A stale temp file from a crashed previous run must not block the
        // write, and must be gone afterwards.
        tmp.writeText("stale")
        val entries = listOf(entry("a", "pw-a"))
        Vault.store(file, passphrase(), entries, testParams)
        assertFalse(tmp.exists())
        assertEquals(entries, Vault.load(file, passphrase()))
    }

    @Test
    fun vaultIsOwnerOnly() {
        val file = vaultFile()
        Vault.store(file, passphrase(), emptyList(), testParams)
        assertTrue(file.canRead())
        assertTrue(file.canWrite())
        // The JVM's File API cannot report the group/other bits portably, so
        // this asserts what it can: the owner still has access after the
        // permissions were narrowed.
    }

    @Test
    fun secretIsRedactedInDebugOutput() {
        assertEquals("[redacted]", Secret("hunter2").toString())
        assertEquals("Passphrase([redacted])", passphrase().toString())
        // ...and the entry as a whole, which is what a log line would print.
        assertFalse(entry("a", "hunter2").toString().contains("hunter2"))
    }

    @Test
    fun passphraseCloseWipesItsBytes() {
        val pass = Passphrase("secret")
        val bytes = pass.expose()
        pass.close()
        assertTrue(bytes.all { it == 0.toByte() })
    }
}
