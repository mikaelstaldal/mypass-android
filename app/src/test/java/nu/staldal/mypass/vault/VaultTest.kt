package nu.staldal.mypass.vault

import java.io.File
import nu.staldal.mypass.crypto.ScryptFormat
import nu.staldal.mypass.crypto.ScryptFormatException
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

    private fun vaultFile() = File(temp.root, "mypass.scrypt")

    @Test
    fun contentBudgetsRejectBeforeJsonMaterialization() {
        for (text in listOf(
            "[" + "0,".repeat(Vault.MAX_JSON_TOKENS) + "0]",
            "[".repeat(Vault.MAX_DEPTH + 1) + "]".repeat(Vault.MAX_DEPTH + 1),
            "[" + List(Vault.MAX_ENTRIES + 1) { "{}" }.joinToString(",") + "]",
            "[\"" + "x".repeat(Vault.MAX_FIELD_CHARS + 1) + "\"]",
        )) {
            assertThrows(VaultException.ResourceLimit::class.java) { Vault.parse(text) }
        }
        assertEquals(emptyList<PasswordEntry>(), Vault.parse("[]"))
        assertEquals(emptyList<PasswordEntry>(), Vault.parse("{\"version\":1,\"entries\":[]}"))
    }

    @Test
    fun maximumEntryCountWithEveryFieldRoundTripsWithinTokenBudget() {
        val entries = List(Vault.MAX_ENTRIES) { index ->
            PasswordEntry("entry-$index", "user", Secret("password"), "https://example.org", "realm")
        }
        assertEquals(entries, Vault.parse(Vault.toJson(entries)))
        val escaped = listOf(entry("quotes", "\"\\[]{}"))
        assertEquals(escaped, Vault.parse(Vault.toJson(escaped)))
        assertThrows(VaultException.InvalidJson::class.java) {
            Vault.parse("]" + "[".repeat(Vault.MAX_DEPTH + 1))
        }
    }

    @Test
    fun oversizedLocalFileIsBounded() {
        val file = vaultFile()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(Vault.MAX_FILE_BYTES.toLong() + 1) }
        passphrase().use { pass ->
            assertThrows(VaultException.ResourceLimit::class.java) { Vault.load(file, pass) }
        }
    }

    @Test
    fun boundedReadAcceptsExactLimitAndRejectsNoProgress() {
        assertEquals(Vault.MAX_FILE_BYTES,
            Vault.readBounded(java.io.ByteArrayInputStream(ByteArray(Vault.MAX_FILE_BYTES))).size)
        val stalled = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertThrows(java.io.IOException::class.java) { Vault.readBounded(stalled) }
    }

    @Test
    fun replacementSurvivesEveryCommitBoundaryAndCanBeRetried() {
        val old = listOf(entry("old", "old-secret"))
        val incoming = listOf(entry("incoming", "new-secret"))
        for (step in Vault.ReplacementStep.entries) {
            val file = File(temp.newFolder(), "mypass.scrypt")
            passphrase().use { Vault.store(file, it, old, testParams) }
            passphrase().use { Vault.store(file, it, old, testParams) }
            Passphrase("new key").use { pass ->
                val failure = assertThrows(VaultException.Write::class.java) {
                    Vault.storeReplacingKey(file, pass, incoming, testParams) {
                        if (it == step) throw java.io.IOException("interrupted")
                    }
                }
                val primaryCommitted = step >= Vault.ReplacementStep.PRIMARY_RENAMED
                assertEquals(primaryCommitted, failure.primaryCommitted)
                Passphrase(if (primaryCommitted) "new key" else passphraseText).use {
                    assertEquals(if (primaryCommitted) incoming else old, Vault.load(file, it))
                }
                if (step >= Vault.ReplacementStep.BACKUP_RENAMED) {
                    assertEquals(incoming, Vault.load(Vault.backupFile(file), pass))
                }
                // Retrying must remove every retained old-key version.
                Vault.storeReplacingKey(file, pass, incoming, testParams)
                for (retained in listOf(file, Vault.backupFile(file))) {
                    assertEquals(incoming, Vault.load(retained, pass))
                    passphrase().use { previous ->
                        val error = assertThrows(VaultException.Format::class.java) {
                            Vault.load(retained, previous)
                        }
                        assertTrue(error.cause is ScryptFormatException.WrongPassphrase)
                    }
                }
                assertFalse(Vault.tempFile(file).exists())
            }
        }
    }

    @Test
    fun roundTrip() {
        val file = vaultFile()
        val entries = listOf(entry("a", "MyPass-a"), entry("b", "MyPass-b"))
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
        val legacy = """[{"name":"a","username":"a-user","password":"MyPass-a"}]"""
        file.writeBytes(
            ScryptFormat.encrypt(legacy.toByteArray(), passphraseText.toByteArray(), testParams)
        )
        assertEquals(listOf(entry("a", "MyPass-a")), Vault.load(file, passphrase()))
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
        val json = Vault.toJson(listOf(entry("a", "MyPass-a")))
        assertEquals(
            """{"version":1,"entries":[{"name":"a","username":"a-user","password":"MyPass-a"}]}""",
            json,
        )
    }

    @Test
    fun urlAndRealmAreOmittedWhenAbsentAndWrittenWhenSet() {
        // The desktop format skips these fields when unset, so a url-less
        // entry stays byte-identical to what MyPass wrote before they existed.
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
        val old = listOf(entry("old", "MyPass-old"))
        val new = listOf(entry("new", "MyPass-new"))
        Vault.store(file, passphrase(), old, testParams)
        Vault.store(file, passphrase(), new, testParams)

        assertEquals(new, Vault.load(file, passphrase()))
        assertEquals(old, Vault.load(Vault.backupFile(file), passphrase()))
    }

    @Test
    fun failedStoreLeavesExistingVaultUntouched() {
        val file = vaultFile()
        val entries = listOf(entry("a", "MyPass-a"))
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
        assertEquals(File(temp.root, "mypass.scrypt.tmp"), tmp)
        // A stale temp file from a crashed previous run must not block the
        // write, and must be gone afterwards.
        tmp.writeText("stale")
        val entries = listOf(entry("a", "MyPass-a"))
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
