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
    private fun TestScope.repository(
        vaultDir: File = temp.root,
        onReplacementCommitted: () -> Unit = {},
        replacementWriter: (File, Passphrase, List<PasswordEntry>, ScryptFormat.Params) -> Unit =
            Vault::storeReplacingKey,
    ) =
        PwRepository(
            vaultDir,
            { testScheduler.currentTime },
            this,
            StandardTestDispatcher(testScheduler),
            replacementWriter,
            onReplacementCommitted,
        ).also { it.scryptLogN = 12 }

    @Test
    fun biometricEnrollmentRejectsTypoAndStaleSession() = runTest {
        val repo = repository().also { it.autoLockMinutes = 0 }
        repo.createVault(passphrase)
        assertFailsWith(PwException.WrongPassphrase::class.java) { repo.verifyBiometricEnrollment("typo") }
        val token = repo.verifyBiometricEnrollment(passphrase)
        repo.lock()
        repo.unlock(passphrase)
        assertFailsWith(PwException.Locked::class.java) {
            repo.completeBiometricEnrollment(token, {}) { error("must not persist") }
        }
        repo.lock()
    }

    @Test
    fun biometricEnrollmentRejectsReplacementAndReportsPersistenceFailure() = runTest {
        val repo = repository().also { it.autoLockMinutes = 0 }
        repo.createVault(passphrase)
        val stale = repo.verifyBiometricEnrollment(passphrase)
        repo.changePassphrase("new passphrase")
        assertFailsWith(PwException.Locked::class.java) {
            repo.completeBiometricEnrollment(stale, {}) { error("must not persist") }
        }
        val token = repo.verifyBiometricEnrollment("new passphrase")
        assertFalse(repo.completeBiometricEnrollment(token, {}) { false })
        assertFailsWith(PwException.Locked::class.java) {
            repo.completeBiometricEnrollment(token, {}) { error("single use") }
        }
        repo.lock()
    }

    @Test
    fun lockAndCancellationDuringEnrollmentVerificationWin() = runTest {
        val repo = repository().also { it.autoLockMinutes = 0 }
        repo.createVault(passphrase)
        val verification = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            assertFailsWith(PwException.Locked::class.java) { repo.verifyBiometricEnrollment(passphrase) }
        }
        repo.lock()
        verification.join()
        repo.unlock(passphrase)
        var completed = false
        val cancelled = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repo.verifyBiometricEnrollment(passphrase)
            completed = true
        }
        cancelled.cancel()
        cancelled.join()
        assertFalse(completed)
        repo.lock()
    }

    @Test
    fun queuedEnrollmentCannotAdoptALaterUnlockedSession() = runTest {
        val repo = repository().also { it.autoLockMinutes = 0 }
        repo.createVault(passphrase)
        val writer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repo.add(entry("one"))
        }
        // Queue an unlock ahead of enrollment while the writer holds the mutex.
        val unlock = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repo.unlock(passphrase)
        }
        val verification = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            assertFailsWith(PwException.Locked::class.java) { repo.verifyBiometricEnrollment(passphrase) }
        }
        repo.lock()
        writer.join()
        unlock.join()
        verification.join()
        repo.lock()
    }

    @Test
    fun enrollmentRefreshesActivityAndDiscardsALockDuringPersistence() = runTest {
        val repo = repository().also { it.autoLockMinutes = 1 }
        repo.createVault(passphrase)
        advanceTimeBy(50_000)
        val token = repo.verifyBiometricEnrollment(passphrase)
        advanceTimeBy(20_000)
        assertTrue(repo.completeBiometricEnrollment(token, {}) { true })
        advanceTimeBy(50_000)
        val next = repo.verifyBiometricEnrollment(passphrase)
        var discarded = false
        assertFailsWith(PwException.Locked::class.java) {
            repo.completeBiometricEnrollment(next, { discarded = true }) {
                // A separate thread must be able to lock while persistence runs.
                val locker = Thread { repo.lock() }
                locker.start()
                locker.join(2_000)
                assertFalse(locker.isAlive)
                true
            }
        }
        assertTrue(discarded)
        repo.lock()
    }

    @Test
    fun cancellationAfterEnrollmentPersistenceDiscardsStorage() = runTest {
        val repo = repository().also { it.autoLockMinutes = 0 }
        repo.createVault(passphrase)
        val token = repo.verifyBiometricEnrollment(passphrase)
        lateinit var operation: kotlinx.coroutines.Job
        var discarded = false
        var completed = false
        operation = launch {
            repo.completeBiometricEnrollment(token, { discarded = true }) {
                operation.cancel()
                true
            }
            completed = true
        }
        operation.join()
        assertTrue(discarded)
        assertFalse(completed)
        repo.lock()
    }

    private fun entry(name: String, password: String = "pw-$name", url: String? = null) =
        PasswordEntry(name, "$name-user", Secret(password), url = url)

    private fun PwRepository.entries() = (state.value as VaultState.Unlocked).entries

    /**
     * `assertThrows` around `runBlocking` would deadlock: the operation
     * suspends onto the test scheduler, which cannot run while `runBlocking`
     * holds the test thread. This awaits the suspending call properly.
     */
    private suspend fun assertFailsWith(type: Class<out Throwable>, block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            if (type.isInstance(e)) return e
            throw AssertionError("expected ${type.name} but was ${e.javaClass.name}", e)
        }
        throw AssertionError("expected ${type.name} but nothing was thrown")
    }

    @Test
    fun endlessImportIsBoundedRunsOnDispatcherAndPreservesVault() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("old"))
        val before = repository.vaultFile.readBytes()
        var readCount = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("bulk reads expected")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(0, off, off + len)
                readCount += len
                return len
            }
        }
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            assertFailsWith(PwException.ResourceLimit::class.java) {
                repository.importVault(input, "wrong")
            }
        }
        // The mutex is uncontended: this pins the repository dispatcher hop before
        // reading, not the ViewModel dispatcher hop around provider opening.
        assertEquals(0, readCount)
        testScheduler.runCurrent()
        job.join()
        assertEquals(Vault.MAX_FILE_BYTES + 1, readCount)
        assertArrayEquals(before, repository.vaultFile.readBytes())
        assertEquals("pw-old", repository.get("old").password.expose())
    }

    @Test
    fun stalledProviderDoesNotHoldRepositoryMutex() = runTest {
        val repository = PwRepository(temp.root, { testScheduler.currentTime }, this,
            kotlinx.coroutines.Dispatchers.IO).also {
            it.scryptLogN = 12
            it.autoLockMinutes = 0
        }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val input = object : java.io.InputStream() {
            override fun read() = error("bulk reads expected")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw java.io.IOException("test provider released")
            }
        }
        val importJob = launch {
            assertFailsWith(PwException.Io::class.java) { repository.importVault(input, "wrong") }
        }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                // Use real time for this real blocked-I/O concurrency test.
                kotlinx.coroutines.withTimeout(5_000) { repository.createVault(passphrase) }
                assertTrue(repository.vaultExists())
            }
        } finally {
            release.countDown()
        }
        importJob.join()
    }

    @Test
    fun lockDuringProviderReadWinsOverImportUnlock() = runTest {
        val repository = repository()
        val data = ScryptFormat.encrypt("{\"version\":1,\"entries\":[]}".toByteArray(),
            passphrase.toByteArray(), ScryptFormat.Params(12, 8, 1))
        val input = object : ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                repository.lock()
                return super.read(b, off, len)
            }
        }
        assertFailsWith(PwException.ReplacementCommitted::class.java) {
            repository.importVault(input, passphrase)
        }
        assertEquals(VaultState.Locked, repository.state.value)
        assertTrue(repository.vaultExists())
    }

    @Test
    fun excessiveImportedJsonPreservesPrimaryAndBackup() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("old"))
        val before = repository.vaultFile.readBytes()
        val backup = Vault.backupFile(repository.vaultFile).readBytes()
        val oversized = ("[".repeat(Vault.MAX_DEPTH + 1) + "]".repeat(Vault.MAX_DEPTH + 1)).toByteArray()
        val encrypted = ScryptFormat.encrypt(oversized, passphrase.toByteArray(), ScryptFormat.Params(12, 8, 1))
        assertFailsWith(PwException.ResourceLimit::class.java) {
            repository.importVault(ByteArrayInputStream(encrypted), passphrase)
        }
        assertArrayEquals(before, repository.vaultFile.readBytes())
        assertArrayEquals(backup, Vault.backupFile(repository.vaultFile).readBytes())
        assertEquals("pw-old", repository.get("old").password.expose())
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

    @Test
    fun incompatibleEncryptedMetadataCannotReplaceOrOpenVault() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("local"))
        val primary = repository.vaultFile.readBytes()
        val backup = Vault.backupFile(repository.vaultFile).readBytes()
        val invalid = listOf(
            listOf(entry("duplicate"), entry("duplicate")),
            listOf(entry("bad\u202Ename")),
            listOf(entry("bad\u0000name")),
            listOf(entry("bad\u200Bname")),
            listOf(entry("x".repeat(257))),
            listOf(entry("valid").copy(username = "bad\u2066user")),
            listOf(entry("valid").copy(url = "x".repeat(257))),
            listOf(entry("valid").copy(realm = "realm")),
        )
        for (entries in invalid) {
            val foreign = temp.newFile()
            Passphrase("incoming").use {
                Vault.store(foreign, it, entries, ScryptFormat.Params(logN = 12, r = 8, p = 1))
            }
            foreign.inputStream().use {
                assertFailsWith(PwException.InvalidInput::class.java) { repository.importVault(it, "incoming") }
            }
            assertArrayEquals(primary, repository.vaultFile.readBytes())
            assertArrayEquals(backup, Vault.backupFile(repository.vaultFile).readBytes())
            assertEquals(listOf(entry("local")), repository.entries())
            val other = repository(temp.newFolder())
            foreign.copyTo(other.vaultFile)
            assertFailsWith(PwException.InvalidInput::class.java) { other.unlock("incoming") }
            assertFalse(other.state.value is VaultState.Unlocked)
            val recovered = ByteArrayOutputStream()
            other.copyVaultTo(recovered)
            assertArrayEquals(foreign.readBytes(), recovered.toByteArray())
        }
        repository.lock()
    }

    @Test
    fun validLegacyArrayStillUnlocks() = runTest {
        val repository = repository()
        val plaintext = """[{"name":"legacy","username":"user","password":"secret"}]""".toByteArray()
        Passphrase(passphrase).use {
            repository.vaultFile.writeBytes(ScryptFormat.encrypt(plaintext, it.expose(), ScryptFormat.Params(logN = 12, r = 8, p = 1)))
        }
        repository.unlock(passphrase)
        assertEquals("secret", repository.get("legacy").password.expose())
        repository.lock()
    }

    @Test
    fun metadataValidationNeverChangesPasswordBytes() = runTest {
        val repository = repository()
        val foreign = temp.newFile()
        val password = "\u202E\u0000\u200B" + "x".repeat(300)
        Passphrase(passphrase).use {
            Vault.store(foreign, it, listOf(entry("valid", password)), ScryptFormat.Params(logN = 12, r = 8, p = 1))
        }
        foreign.inputStream().use { repository.importVault(it, passphrase) }
        assertEquals(password, repository.get("valid").password.expose())
        repository.lock()
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
    fun replacementFailuresPreserveStateUntilPrimaryCommits() = runTest {
        for (step in Vault.ReplacementStep.entries) {
            var invalidations = 0
            val repository = repository(temp.newFolder(), { invalidations++ }) { file, pass, entries, params ->
                Vault.storeReplacingKey(file, pass, entries, params) {
                    if (it == step) throw java.io.IOException("interrupted")
                }
            }
            repository.createVault(passphrase)
            repository.add(entry("local"))
            val committed = step >= Vault.ReplacementStep.PRIMARY_RENAMED
            assertFailsWith(
                if (committed) PwException.ReplacementCommitted::class.java else PwException.Io::class.java,
            ) { repository.changePassphrase("new key") }
            assertEquals(if (committed) 1 else 0, invalidations)
            if (committed) {
                assertTrue(repository.state.value is VaultState.Locked)
                assertFailsWith(PwException.Locked::class.java) { repository.add(entry("blocked")) }
                repository.unlock("new key")
            } else {
                assertEquals(listOf(entry("local")), repository.entries())
                // A subsequent edit must still use the original passphrase.
                repository.add(entry("after-failure"))
                Passphrase(passphrase).use {
                    assertEquals(
                        listOf(entry("local"), entry("after-failure")),
                        Vault.load(repository.vaultFile, it),
                    )
                }
            }
            repository.lock()
        }
    }

    @Test
    fun lockDuringReplacementKeepsVaultLockedButReportsIncomingKey() = runTest {
        for (importing in listOf(false, true)) {
            var invalidations = 0
            lateinit var repository: PwRepository
            repository = repository(temp.newFolder(), { invalidations++ }) { file, pass, entries, params ->
                Vault.storeReplacingKey(file, pass, entries, params)
                repository.lock()
            }
            repository.createVault(passphrase)
            repository.add(entry("local"))
            assertFailsWith(PwException.ReplacementCommitted::class.java) {
                if (importing) {
                    val foreign = File(temp.newFolder(), "foreign.scrypt")
                    Passphrase("incoming key").use {
                        Vault.store(foreign, it, listOf(entry("imported")), ScryptFormat.Params(logN = 12, r = 8, p = 1))
                    }
                    foreign.inputStream().use { repository.importVault(it, "incoming key") }
                } else {
                    repository.changePassphrase("incoming key")
                }
            }
            assertEquals(1, invalidations)
            assertTrue(repository.state.value is VaultState.Locked)
            assertFailsWith(PwException.WrongPassphrase::class.java) { repository.unlock(passphrase) }
            repository.unlock("incoming key")
            assertEquals(listOf(entry(if (importing) "imported" else "local")), repository.entries())
            repository.lock()
        }
    }

    @Test
    fun cleanupFailureStillReportsCommittedReplacementAndLocks() = runTest {
        for (cleanupError in listOf(IllegalStateException("cleanup failed"), AssertionError("cleanup failed"))) {
            for (durabilityFailure in listOf(false, true)) {
                val repository = repository(
                    temp.newFolder(),
                    onReplacementCommitted = { throw cleanupError },
                ) { file, pass, entries, params ->
                    Vault.storeReplacingKey(file, pass, entries, params) {
                        if (durabilityFailure && it == Vault.ReplacementStep.PRIMARY_RENAMED) {
                            throw java.io.IOException("sync failed")
                        }
                    }
                }
                repository.autoLockMinutes = 0
                repository.createVault(passphrase)
                val failure = assertFailsWith(PwException.ReplacementCommitted::class.java) {
                    repository.changePassphrase("incoming key")
                }
                assertEquals(durabilityFailure, failure.message!!.contains("Durability could not be confirmed"))
                assertEquals(!durabilityFailure, failure.message!!.contains("vault remains locked"))
                if (durabilityFailure) {
                    assertTrue(failure.cause is nu.staldal.pw.vault.VaultException.Write)
                    assertTrue((failure.cause as nu.staldal.pw.vault.VaultException.Write).primaryCommitted)
                    assertEquals(listOf(cleanupError), failure.suppressed.toList())
                } else {
                    assertTrue(failure.cause === cleanupError)
                }
                assertEquals(VaultState.Locked, repository.state.value)
                assertFailsWith(PwException.Locked::class.java) { repository.add(entry("blocked")) }
                assertFailsWith(PwException.WrongPassphrase::class.java) { repository.unlock(passphrase) }
                repository.unlock("incoming key")
                repository.lock()
            }
        }
    }

    @Test
    fun onlyCommittedReplacementsInvalidateCredentials() = runTest {
        var invalidations = 0
        val repository = repository(onReplacementCommitted = { invalidations++ })
        repository.autoLockMinutes = 0
        repository.createVault(passphrase)
        repository.add(entry("local"))
        repository.lock()
        repository.unlock(passphrase)
        assertEquals(0, invalidations)
        repository.changePassphrase("incoming key")
        assertEquals(1, invalidations)
        assertTrue(repository.state.value is VaultState.Unlocked)
        assertFailsWith(PwException.WrongPassphrase::class.java) {
            repository.importVault(ByteArrayInputStream(repository.vaultFile.readBytes()), "wrong")
        }
        assertEquals(1, invalidations)
        repository.importVault(ByteArrayInputStream(repository.vaultFile.readBytes()), "incoming key")
        assertEquals(2, invalidations)
        assertTrue(repository.state.value is VaultState.Unlocked)
        repository.lock()
    }

    @Test
    fun cancelledReplacementInvalidatesCredentialsWithoutUiSuccess() = runTest {
        for (importing in listOf(false, true)) {
            var invalidations = 0
            var uiSuccess = false
            lateinit var operation: kotlinx.coroutines.Job
            val repository = repository(temp.newFolder(), { invalidations++ }) { file, pass, entries, params ->
                Vault.storeReplacingKey(file, pass, entries, params)
                // Cancel the initiating screen after commit, before the
                // dispatcher can deliver the result to openAfter or the UI.
                operation.cancel()
            }
            repository.autoLockMinutes = 0
            repository.createVault(passphrase)
            repository.add(entry("local"))
            val foreign = File(temp.newFolder(), "foreign.scrypt")
            Passphrase("incoming key").use {
                Vault.store(foreign, it, listOf(entry("imported")), ScryptFormat.Params(12, 8, 1))
            }
            operation = launch {
                if (importing) {
                    foreign.inputStream().use { repository.importVault(it, "incoming key") }
                } else {
                    repository.changePassphrase("incoming key")
                }
                uiSuccess = true
            }
            operation.join()
            assertTrue(operation.isCancelled)
            assertFalse(uiSuccess)
            assertEquals(1, invalidations)
            assertEquals(VaultState.Locked, repository.state.value)
            assertFailsWith(PwException.Locked::class.java) { repository.add(entry("blocked")) }
            repository.unlock("incoming key")
            assertEquals(listOf(entry(if (importing) "imported" else "local")), repository.entries())
            repository.lock()
        }
    }

    @Test
    fun importFailureAfterCommitLocksAndRequiresIncomingPassphrase() = runTest {
        val repository = repository { file, pass, entries, params ->
            Vault.storeReplacingKey(file, pass, entries, params) {
                if (it == Vault.ReplacementStep.PRIMARY_RENAMED) throw java.io.IOException("interrupted")
            }
        }
        repository.createVault(passphrase)
        repository.add(entry("local"))
        val foreign = File(temp.root, "foreign.scrypt")
        Passphrase("incoming key").use {
            Vault.store(foreign, it, listOf(entry("imported")), ScryptFormat.Params(logN = 12, r = 8, p = 1))
        }
        assertFailsWith(PwException.ReplacementCommitted::class.java) {
            foreign.inputStream().use { repository.importVault(it, "incoming key") }
        }
        assertTrue(repository.state.value is VaultState.Locked)
        assertFailsWith(PwException.WrongPassphrase::class.java) { repository.unlock(passphrase) }
        repository.unlock("incoming key")
        assertEquals(listOf(entry("imported")), repository.entries())
    }

    @Test
    fun encryptionFailureDuringRotationLeavesCurrentVaultUnlocked() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.scryptLogN = 0
        assertFailsWith(PwException.CorruptVault::class.java) { repository.changePassphrase("new key") }
        assertTrue(repository.state.value is VaultState.Unlocked)
        Passphrase(passphrase).use { assertEquals(emptyList<PasswordEntry>(), Vault.load(repository.vaultFile, it)) }
    }

    @Test
    fun changePassphraseReEncryptsWithoutTouchingTheEntries() = runTest {
        val repository = repository()
        repository.createVault(passphrase)
        repository.add(entry("a"))
        // Residue from an interrupted ordinary write must not survive rotation.
        repository.vaultFile.copyTo(Vault.tempFile(repository.vaultFile))
        repository.changePassphrase("a different passphrase")
        assertFalse(Vault.tempFile(repository.vaultFile).exists())

        Passphrase("a different passphrase").use {
            assertEquals(listOf(entry("a")), Vault.load(Vault.backupFile(repository.vaultFile), it))
        }
        assertThrows(nu.staldal.pw.vault.VaultException.Format::class.java) {
            Passphrase(passphrase).use { Vault.load(Vault.backupFile(repository.vaultFile), it) }
        }
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
        // Import retains only a snapshot protected by the incoming key.
        assertEquals(
            listOf("imported"),
            Vault.load(Vault.backupFile(repository.vaultFile), Passphrase("other passphrase"))
                .map { it.name },
        )
        for (retained in listOf(repository.vaultFile, Vault.backupFile(repository.vaultFile))) {
            assertThrows(nu.staldal.pw.vault.VaultException.Format::class.java) {
                Passphrase(passphrase).use { Vault.load(retained, it) }
            }
            Passphrase("other passphrase").use {
                assertEquals(listOf("imported"), Vault.load(retained, it).map { entry -> entry.name })
            }
        }
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
