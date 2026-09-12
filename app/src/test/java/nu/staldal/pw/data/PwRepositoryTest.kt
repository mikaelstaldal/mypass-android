package nu.staldal.pw.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nu.staldal.pw.crypto.ScryptFormat
import nu.staldal.pw.vault.Passphrase
import nu.staldal.pw.vault.PasswordEntry
import nu.staldal.pw.vault.Secret
import nu.staldal.pw.vault.Vault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The domain operations, which on the desktop are `pw init/get/list/add/
 * update/remove/export` — plus the unlocked-vault state the CLI does not need.
 *
 * The repository's clock is the test scheduler's virtual time and its timer
 * runs in the test scope, so auto-lock is tested by advancing time rather than
 * by waiting: `advanceTimeBy` moves the deadline and the clock together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PwRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val passphrase = "test passphrase"

    /**
     * The KDF and the file write run on the test scheduler too, not on
     * `Dispatchers.Default`. Otherwise `runTest` would find itself with
     * nothing to run while the real thread works, fast-forward virtual time to
     * the next delayed task — which is the auto-lock expiry — and relock the
     * vault in the middle of an unrelated test.
     *
     * logN is lowered because the default N=2^17 takes seconds per write, and
     * these tests do a lot of them.
     */
    private fun TestScope.repository() =
        PwRepository(
            temp.root,
            { testScheduler.currentTime },
            this,
            StandardTestDispatcher(testScheduler),
        ).also { it.scryptLogN = 12 }

    private fun entry(name: String, password: String = "pw-$name", url: String? = null) =
        PasswordEntry(name, "$name-user", Secret(password), url = url)

    private fun PwRepository.entries() = (state.value as VaultState.Unlocked).entries

    /**
     * `assertThrows` around `runBlocking` would deadlock: the operation
     * suspends onto the test scheduler, which cannot run while `runBlocking`
     * holds the test thread. This awaits the suspending call properly.
     */
    private suspend fun assertFailsWith(type: Class<out Throwable>, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (type.isInstance(e)) return
            throw AssertionError("expected ${type.name} but was ${e.javaClass.name}", e)
        }
        throw AssertionError("expected ${type.name} but nothing was thrown")
    }

    @Test
    fun createThenListEmpty() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        assertTrue(repository.vaultExists())
        assertEquals(emptyList<PasswordEntry>(), repository.entries())
    }

    @Test
    fun createRefusesExistingVault() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        assertFailsWith(PwException.VaultAlreadyExists::class.java) { repository.createVault(passphrase) }
    }

    @Test
    fun addThenGet() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        repository.add(entry("b"))
        assertEquals(entry("b"), repository.get("b"))
    }

    @Test
    fun addRefusesDuplicateName() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        assertFailsWith(PwException.AlreadyExists::class.java) { repository.add(entry("a", "other")) }
        assertEquals("pw-a", repository.get("a").password.expose())
    }

    @Test
    fun addRejectsInvalidEntry() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        assertFailsWith(PwException.InvalidInput::class.java) { repository.add(entry("")) }
        assertEquals(emptyList<PasswordEntry>(), repository.entries())
    }

    @Test
    fun getUnknownName() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        assertThrows(PwException.NotFound::class.java) { repository.get("nothing") }
    }

    @Test
    fun updateReplacesEntry() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        repository.update("a", entry("a", "new-pw", url = "github.com"))
        assertEquals("new-pw", repository.get("a").password.expose())
        assertEquals("github.com", repository.get("a").url)
        assertEquals(1, repository.entries().size)
    }

    @Test
    fun updateCanRenameButNotOntoAnExistingName() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        repository.add(entry("b"))
        repository.update("a", entry("c"))
        assertEquals(listOf("c", "b"), repository.entries().map { it.name })

        assertFailsWith(PwException.AlreadyExists::class.java) { repository.update("c", entry("b")) }
        assertFailsWith(PwException.NotFound::class.java) { repository.update("gone", entry("d")) }
    }

    @Test
    fun removeDeletesOnlyThatEntry() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        repository.add(entry("b"))
        repository.remove("a")
        assertEquals(listOf("b"), repository.entries().map { it.name })
        assertFailsWith(PwException.NotFound::class.java) { repository.remove("a") }
    }

    @Test
    fun changesSurviveALockAndUnlock() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a", url = "github.com"))
        repository.lock()
        assertNull(repository.entriesOrNull())

        repository.unlock(passphrase)
        assertEquals(listOf(entry("a", url = "github.com")), repository.entries())
    }

    @Test
    fun unlockWithTheWrongPassphraseFails() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.lock()
        assertFailsWith(PwException.WrongPassphrase::class.java) { repository.unlock("wrong") }
        assertNull(repository.entriesOrNull())
    }

    @Test
    fun unlockingWithoutAVaultFails() = runTest {
        val repository = repository()
        assertFailsWith(PwException.NoVault::class.java) { repository.unlock(passphrase) }
    }

    @Test
    fun operationsOnALockedVaultFail() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.lock()
        assertFailsWith(PwException.Locked::class.java) { repository.add(entry("a")) }
        assertThrows(PwException.Locked::class.java) { repository.get("a") }
        assertThrows(PwException.Locked::class.java) { repository.exportJson() }
    }

    @Test
    fun aFailedCreateLeavesNothingUnlocked() = runTest {
        val repository = repository()
        // An impossible KDF cost makes the write fail after the passphrase has
        // been taken, exercising the path where it must be wiped rather than
        // abandoned.
        repository.scryptLogN = 0
        assertFailsWith(PwException.CorruptVault::class.java) { repository.createVault(passphrase) }
        assertNull(repository.entriesOrNull())
        assertFalse(repository.vaultExists())
    }

    // --- auto-lock -------------------------------------------------------

    @Test
    fun theTimerRelocksWithNobodyAsking() = runTest {
        // The regression this exists for: auto-lock used to happen only when
        // something called into the repository, so a screen sitting on a
        // revealed password — which asks nothing while it sits there — kept
        // the vault open for as long as it was left there.
        val repository = repository()
        repository.autoLockMinutes = 5
        repository.createVault(passphrase)
        repository.add(entry("a"))

        advanceTimeBy(5 * 60_000L + 1)
        assertTrue(repository.state.value is VaultState.Locked)
    }

    @Test
    fun theTimerDoesNotFireEarly() = runTest {
        val repository = repository()
        repository.autoLockMinutes = 5
        repository.createVault(passphrase)

        advanceTimeBy(4 * 60_000L)
        assertTrue(repository.state.value is VaultState.Unlocked)
    }

    @Test
    fun autoLockRelocksAfterTheWindow() = runTest {
        val repository = repository()
        repository.autoLockMinutes = 5
        repository.createVault(passphrase)
        repository.add(entry("a"))

        advanceTimeBy(4 * 60_000L)
        assertNotNull(repository.entriesOrNull())

        advanceTimeBy(5 * 60_000L)
        assertNull(repository.entriesOrNull())
    }

    @Test
    fun autoLockWindowSlidesOnEveryAccess() = runTest {
        val repository = repository()
        repository.autoLockMinutes = 5
        repository.createVault(passphrase)

        // Read just before each window expires: the vault stays open long past
        // five minutes of wall time, because it was never idle for five.
        repeat(4) {
            advanceTimeBy(4 * 60_000L)
            assertNotNull(repository.entriesOrNull())
        }
        advanceTimeBy(6 * 60_000L)
        assertNull(repository.entriesOrNull())
    }

    @Test
    fun autoLockCanBeTurnedOff() = runTest {
        val repository = repository()
        repository.autoLockMinutes = 0
        repository.createVault(passphrase)
        advanceTimeBy(365L * 24 * 60 * 60 * 1000)
        assertNotNull(repository.entriesOrNull())
    }

    @Test
    fun shorteningTheWindowInSettingsTakesEffectAtOnce() = runTest {
        val repository = repository()
        repository.autoLockMinutes = 60
        repository.createVault(passphrase)
        advanceTimeBy(10 * 60_000L)
        assertNotNull(repository.entriesOrNull())

        // The pending hour-long timer must be replaced, not left armed.
        repository.autoLockMinutes = 1
        advanceTimeBy(60_000L + 1)
        assertTrue(repository.state.value is VaultState.Locked)
    }

    @Test
    fun lockingWhileAnUnlockIsInFlightWins() = runTest {
        // This one deliberately runs the KDF on a real dispatcher, so the lock
        // genuinely races it rather than being ordered by the test scheduler.
        // Auto-lock is off so the only thing that can lock the vault is the
        // explicit call below.
        val repository = PwRepository(temp.root, { testScheduler.currentTime }, this)
            .also { it.scryptLogN = 12 }
        repository.autoLockMinutes = 0
        repository.createVault(passphrase)
        repository.lock()

        // Let the unlock get as far as its KDF, then lock from "another
        // thread". Whichever way the two interleave the vault must end up
        // locked: either lock() ran first and the unlock refused to open, or
        // the unlock opened and lock() closed it.
        val unlocking = launch { runCatching { repository.unlock(passphrase) } }
        advanceUntilIdle()
        repository.lock()
        unlocking.join()

        assertTrue(repository.state.value is VaultState.Locked)
        assertNull(repository.entriesOrNull())
    }

    // --- import and export ----------------------------------------------

    @Test
    fun exportJsonIsTheSameEnvelopeThatIsStored() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a", url = "github.com"))
        assertEquals(
            """{"version":1,"entries":[{"name":"a","username":"a-user",""" +
                """"password":"pw-a","url":"github.com"}]}""",
            repository.exportJson(),
        )
    }

    @Test
    fun changePassphraseReEncryptsWithoutTouchingTheEntries() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        repository.changePassphrase("a different passphrase")

        repository.lock()
        assertFailsWith(PwException.WrongPassphrase::class.java) { repository.unlock(passphrase) }
        repository.unlock("a different passphrase")
        assertEquals(listOf(entry("a")), repository.entries())
    }

    @Test
    fun copyVaultToWritesTheEncryptedFileUnchanged() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        val out = ByteArrayOutputStream()
        repository.copyVaultTo(out)
        assertArrayEquals(File(temp.root, "pw.scrypt").readBytes(), out.toByteArray())
    }

    @Test
    fun importReplacesTheVaultAfterVerifyingIt() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("local"))

        // A vault as another device would have written it.
        val foreign = File(temp.root, "foreign.scrypt")
        Vault.store(
            foreign,
            Passphrase("other passphrase"),
            listOf(entry("imported", url = "example.com")),
            ScryptFormat.Params(logN = 12, r = 8, p = 1),
        )

        repository.importVault(foreign.inputStream(), "other passphrase")
        assertEquals(listOf("imported"), repository.entries().map { it.name })
        // The previous vault is still there as the backup.
        assertEquals(
            listOf("local"),
            Vault.load(Vault.backupFile(repository.vaultFile), Passphrase(passphrase))
                .map { it.name },
        )
    }

    @Test
    fun importWithTheWrongPassphraseLeavesTheVaultAlone() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("local"))

        val foreign = File(temp.root, "foreign.scrypt")
        Vault.store(
            foreign,
            Passphrase("other passphrase"),
            listOf(entry("imported")),
            ScryptFormat.Params(logN = 12, r = 8, p = 1),
        )

        val before = repository.vaultFile.readBytes()
        assertFailsWith(PwException.WrongPassphrase::class.java) { repository.importVault(foreign.inputStream(), "wrong") }
        assertEquals(listOf("local"), repository.entries().map { it.name })
        // Not one byte of the vault was written: the import is verified first.
        assertArrayEquals(before, repository.vaultFile.readBytes())
    }

    @Test
    fun importOfSomethingThatIsNotAVaultIsRefused() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        assertFailsWith(PwException.CorruptVault::class.java) {
            repository.importVault(ByteArrayInputStream("not a vault".toByteArray()), "x")
        }
        assertNotNull(repository.entriesOrNull())
    }
}
