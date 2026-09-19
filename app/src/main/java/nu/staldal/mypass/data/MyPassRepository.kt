package nu.staldal.mypass.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nu.staldal.mypass.crypto.ScryptFormat
import nu.staldal.mypass.crypto.ScryptFormatException
import nu.staldal.mypass.vault.PasswordEntry
import nu.staldal.mypass.vault.Passphrase
import nu.staldal.mypass.vault.Vault
import nu.staldal.mypass.vault.VaultException

/** Whether the vault is open, and what is in it when it is. */
sealed interface VaultState {
    data object Locked : VaultState
    data class Unlocked(val entries: List<PasswordEntry>) : VaultState
}

/**
 * The domain operations of desktop `MyPass` — init, get, list, add, update,
 * remove, export — over a single vault file, plus the unlocked-vault state the
 * CLI does not need.
 *
 * The CLI re-derives the key from a freshly typed passphrase on every command.
 * A phone cannot ask for a 30-character passphrase per operation, so this
 * holds the decrypted entries and the passphrase in memory while the vault is
 * unlocked, bounded by [autoLockMinutes] — the same bargain the desktop
 * browser host strikes with `cache_minutes`.
 *
 * One instance per process, shared by the UI and the autofill service, so
 * unlocking in one opens the vault for the other.
 *
 * **Locking.** Two locks, for two different jobs. [mutex] serializes the slow
 * operations — the scrypt KDF and the file write — so two of them never
 * interleave. [stateLock], a plain monitor, guards the state machine itself:
 * the passphrase, the auto-lock deadline, the expiry timer and [_state] are
 * read and written under it and nowhere else, so [lock] can be called from any
 * thread at any moment without losing a race against an unlock in flight or
 * handing out entries the instant after it ran.
 */
class MyPassRepository(
    private val vaultDir: File,
    /**
     * Monotonic milliseconds for the auto-lock window — `SystemClock`'s
     * elapsed-realtime in the app, the test scheduler's virtual time in tests.
     * A wall clock would let a timezone change or an NTP step move the lock
     * time, which is not what "five minutes of inactivity" should mean.
     */
    private val elapsedMillis: () -> Long,
    /**
     * Where the auto-lock timer runs. Application-lifetime in the app: the
     * vault must relock on time even when no screen is asking anything of it.
     */
    private val scope: CoroutineScope,
    /**
     * Where the slow work — the scrypt KDF and the file write — runs, so it
     * never lands on the main thread. Injectable so tests can put it on the
     * same scheduler as the timer and stay deterministic.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Injectable replacement I/O for testing failures after a primary commit. */
    private val replacementWriter: (File, Passphrase, List<PasswordEntry>, ScryptFormat.Params) -> Unit =
        Vault::storeReplacingKey,
    /**
     * Application integration hook for invalidating credentials after a key
     * replacement commits. Runs synchronously on the writer thread, including
     * after a committed durability failure, before any cancellable dispatcher
     * return. Must not prompt or depend on a screen's lifetime.
     */
    private val onReplacementCommitted: () -> Unit = {},
) {

    /** Read by the UI so it can re-render when the vault opens or closes. */
    private val _state = MutableStateFlow<VaultState>(VaultState.Locked)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** Serializes the slow operations: the KDF and the file write. */
    private val mutex = Mutex()

    /** Guards every field below, and every read or write of [_state]. */
    private val stateLock = Any()

    private var passphrase: Passphrase? = null
    private var lastAccessMillis: Long = 0
    private var expiryJob: Job? = null
    private var autoLock: Int = SettingsState.DEFAULT_AUTO_LOCK_MINUTES

    /**
     * Counts [lock] calls. An operation that opens the vault captures this
     * before its slow part and refuses to open if it has moved since: a lock
     * that arrives while the KDF is still grinding must win, or backgrounding
     * the app mid-unlock would open the vault behind the user's back.
     */
    private var lockEpoch: Long = 0
    private var vaultGeneration: Long = 0

    /**
     * Minutes of inactivity before the vault relocks; 0 disables the timer.
     * Mirrors [SettingsState.autoLockMinutes]; assigning it reschedules the
     * pending expiry, so a change in Settings takes effect at once.
     */
    var autoLockMinutes: Int
        get() = synchronized(stateLock) { autoLock }
        set(value) = synchronized(stateLock) {
            autoLock = value
            scheduleExpiryLocked()
        }

    /** Mirrors [SettingsState.scryptLogN]; used for every write. */
    @Volatile
    var scryptLogN: Int = ScryptDefaults.LOG_N

    val vaultFile: File get() = File(vaultDir, VAULT_FILE_NAME)

    fun vaultExists(): Boolean = vaultFile.exists()

    /**
     * The unlocked entries, or `null` if the vault is locked — including when
     * it expired and this call is what noticed. Reading restarts the window.
     */
    fun entriesOrNull(): List<PasswordEntry>? = synchronized(stateLock) {
        if (expiredLocked()) lockLocked()
        val entries = (_state.value as? VaultState.Unlocked)?.entries
            ?: return@synchronized null
        lastAccessMillis = elapsedMillis()
        scheduleExpiryLocked()
        entries
    }

    /**
     * Relock if the window has already passed.
     *
     * The timer normally gets there first, but a process that was frozen or
     * dozing may not have run it on time, so the app also calls this when it
     * returns to the foreground.
     */
    fun enforceAutoLock() = synchronized(stateLock) {
        if (expiredLocked()) lockLocked()
    }

    /**
     * Close the vault: forget the entries and wipe the passphrase. Safe from
     * any thread at any time — including while a write is in flight, which
     * works from its own copy of the passphrase and will not reopen the vault
     * afterwards.
     */
    fun lock() = synchronized(stateLock) { lockLocked() }

    /** Create a new empty vault. Fails if the file already exists. */
    suspend fun createVault(newPassphrase: String) = mutex.withLock {
        val file = vaultFile
        if (file.exists()) throw MyPassException.VaultAlreadyExists(file)
        vaultDir.mkdirs()
        openAfter(Passphrase(newPassphrase)) { pass ->
            withContext(ioDispatcher) { writeVault(file, pass, emptyList()) }
            emptyList()
        }
    }

    /** Decrypt the vault and hold it open. */
    suspend fun unlock(enteredPassphrase: String) = mutex.withLock {
        val file = vaultFile
        if (!file.exists()) throw MyPassException.NoVault(file)
        openAfter(Passphrase(enteredPassphrase)) { pass ->
            try {
                withContext(ioDispatcher) { Vault.load(file, pass).also(Validation::validateEntries) }
            } catch (e: VaultException) {
                throw mapVaultException(file, e)
            }
        }
    }

    /** A single-use enrollment authorization tied to this repository session and key. */
    class Enrollment internal constructor(
        internal val owner: MyPassRepository,
        internal val epoch: Long,
        internal val generation: Long,
    ) {
        internal var consumed = false
    }

    /** Verify without reopening the vault. Snapshot before waiting so a lock or replacement wins. */
    suspend fun verifyBiometricEnrollment(enteredPassphrase: String): Enrollment {
        val token = synchronized(stateLock) {
            requireUnlocked()
            Enrollment(this, lockEpoch, vaultGeneration)
        }
        return mutex.withLock {
            synchronized(stateLock) { requireEnrollmentLocked(token) }
            Passphrase(enteredPassphrase).use { pass ->
                try {
                    withContext(ioDispatcher) { Vault.load(vaultFile, pass).also(Validation::validateEntries) }
                } catch (e: VaultException) {
                    throw mapVaultException(vaultFile, e)
                }
            }
            synchronized(stateLock) {
                requireEnrollmentLocked(token)
                lastAccessMillis = elapsedMillis()
                scheduleExpiryLocked()
            }
            token
        }
    }

    private fun requireEnrollmentLocked(token: Enrollment) {
        if (expiredLocked()) lockLocked()
        if (token.owner !== this || lockEpoch != token.epoch ||
            vaultGeneration != token.generation || _state.value !is VaultState.Unlocked
        ) throw MyPassException.Locked()
    }

    /**
     * Serialize persistence with replacements. [discard] must remove attempted storage
     * without prompting; it runs if locking or cancellation wins during persistence.
     * Both callbacks must avoid throwing; [store] reports persistence failure as false.
     */
    suspend fun completeBiometricEnrollment(
        token: Enrollment,
        discard: () -> Unit,
        store: () -> Boolean,
    ): Boolean = mutex.withLock {
        var attempted = false
        try {
            withContext(ioDispatcher) {
                synchronized(stateLock) {
                    requireEnrollmentLocked(token)
                    if (token.consumed) throw MyPassException.Locked()
                    // Consume on attempt: an authenticated cipher cannot safely be reused after failure.
                    token.consumed = true
                }
                attempted = true
                val stored = store()
                try {
                    synchronized(stateLock) {
                        requireEnrollmentLocked(token)
                        if (stored) {
                            lastAccessMillis = elapsedMillis()
                            scheduleExpiryLocked()
                        }
                    }
                } catch (e: MyPassException.Locked) {
                    discard()
                    throw e
                }
                stored
            }
        } catch (e: CancellationException) {
            // Cancellation can arrive at the dispatcher return after persistence.
            if (attempted) withContext(NonCancellable + ioDispatcher) { discard() }
            throw e
        }
    }

    /** Look up the entry named [name] in the open vault. */
    fun get(name: String): PasswordEntry =
        requireUnlocked().find { it.name == name } ?: throw MyPassException.NotFound(name)

    /** Add a new entry. Fails if an entry with the same name exists. */
    suspend fun add(entry: PasswordEntry) = mutate { entries ->
        Validation.validateEntry(entry)
        if (entries.any { it.name == entry.name }) throw MyPassException.AlreadyExists(entry.name)
        entries + entry
    }

    /**
     * Replace the entry named [name]. The replacement may be named something
     * else, which is a rename — refused if the new name is taken.
     */
    suspend fun update(name: String, entry: PasswordEntry) = mutate { entries ->
        Validation.validateEntry(entry)
        val index = entries.indexOfFirst { it.name == name }
        if (index < 0) throw MyPassException.NotFound(name)
        if (entry.name != name && entries.any { it.name == entry.name }) {
            throw MyPassException.AlreadyExists(entry.name)
        }
        entries.toMutableList().apply { set(index, entry) }
    }

    /** Remove the entry named [name]. */
    suspend fun remove(name: String) = mutate { entries ->
        if (entries.none { it.name == name }) throw MyPassException.NotFound(name)
        entries.filter { it.name != name }
    }

    /**
     * Re-encrypt the vault under a new passphrase. The entries are unchanged;
     * only the KDF salt, derived keys and ciphertext are.
     */
    suspend fun changePassphrase(newPassphrase: String) = mutex.withLock {
        val entries = requireUnlocked()
        openAfter(Passphrase(newPassphrase), replacingKey = true) { pass ->
            withContext(ioDispatcher) { writeVault(vaultFile, pass, entries, replacingKey = true) }
            entries
        }
    }

    /**
     * The decrypted vault as JSON — the same envelope that is stored
     * encrypted — for backup and migration.
     */
    fun exportJson(): String = Vault.toJson(requireUnlocked())

    /**
     * Copy the encrypted vault file out as-is, for backup or for moving it to
     * another device. Needs no passphrase: the bytes stay encrypted.
     */
    fun copyVaultTo(out: OutputStream) {
        val file = vaultFile
        if (!file.exists()) throw MyPassException.NoVault(file)
        try {
            file.inputStream().use { it.copyTo(out) }
        } catch (e: IOException) {
            throw MyPassException.Io(VaultException.Read(file, e))
        }
    }

    /**
     * Replace the vault with an scrypt-format file from elsewhere — typically
     * a `mypass.scrypt` copied off a desktop.
     *
     * The import is verified before it lands: the bytes must parse as the
     * scrypt format and decrypt to a usable vault under [importPassphrase], so
     * a mistyped passphrase or a truncated copy is refused while the existing
     * vault is still there. The local backup is replaced with a snapshot of
     * the imported vault under its passphrase before replacing the primary.
     */
    suspend fun importVault(input: InputStream, importPassphrase: String) {
        // Ciphertext reads need no repository mutex. A stalled provider must
        // not prevent unlocks/edits, but a lock during the read still wins.
        val epoch = synchronized(stateLock) { lockEpoch }
        val data = try {
            withContext(ioDispatcher) { Vault.readBounded(input) }
        } catch (e: VaultException) {
            throw mapVaultException(vaultFile, e)
        } catch (e: IOException) {
            throw MyPassException.Io(VaultException.Read(vaultFile, e))
        }
        mutex.withLock {
            openAfter(Passphrase(importPassphrase), replacingKey = true, epoch = epoch) { pass ->
                val entries = try {
                    withContext(ioDispatcher) {
                        val plaintext = ScryptFormat.decrypt(data, pass.expose())
                        try {
                            Vault.parse(plaintext.toString(Charsets.UTF_8)).also(Validation::validateEntries)
                        } finally {
                            plaintext.fill(0)
                        }
                    }
                } catch (e: ScryptFormatException) {
                    throw mapVaultException(vaultFile, VaultException.Format(e))
                } catch (e: VaultException) {
                    throw mapVaultException(vaultFile, e)
                }
                vaultDir.mkdirs()
                // Re-encrypt rather than copying the bytes, so the vault on this
                // device always uses this device's configured KDF cost.
                withContext(ioDispatcher) { writeVault(vaultFile, pass, entries, replacingKey = true) }
                entries
            }
        }
    }

    /** Add every live entry from a KeePass KDBX database to the open vault. */
    suspend fun importKeePass(input: InputStream, importPassphrase: String) {
        val imported = withContext(ioDispatcher) { KeePassImport.decode(input, importPassphrase) }
        mutate { entries ->
            val names = entries.asSequence().mapTo(mutableSetOf()) { it.name }
            val duplicate = imported.firstOrNull { !names.add(it.name) }
            if (duplicate != null) throw MyPassException.AlreadyExists(duplicate.name)
            val merged = entries + imported
            if (merged.size > Vault.MAX_ENTRIES) {
                throw MyPassException.InvalidInput("KeePass file", "would exceed ${Vault.MAX_ENTRIES} vault entries")
            }
            merged
        }
    }

    /**
     * Run [produce] — the slow, failure-prone half of opening the vault — and
     * open it with the result.
     *
     * [pass] is owned by this function until [openLocked] takes it: on any
     * failure, and on a [lock] that arrived while [produce] was running, it is
     * wiped here rather than left for the garbage collector.
     */
    private suspend fun openAfter(
        pass: Passphrase,
        replacingKey: Boolean = false,
        epoch: Long = synchronized(stateLock) { lockEpoch },
        produce: suspend (Passphrase) -> List<PasswordEntry>,
    ) {
        var owned = true
        try {
            val entries = produce(pass)
            synchronized(stateLock) {
                if (lockEpoch != epoch) {
                    // The replacement write returned successfully, but a lock
                    // must still win. Report the committed key to the UI.
                    if (replacingKey) throw MyPassException.ReplacementCommitted(null, durabilityUnconfirmed = false)
                    throw MyPassException.Locked()
                }
                openLocked(pass, entries)
                owned = false
            }
        } catch (e: CancellationException) {
            // A dispatcher return may be cancelled after storage committed.
            // Never retain the old in-memory key for a subsequent edit.
            if (replacingKey) lock()
            throw e
        } finally {
            if (owned) pass.close()
        }
    }

    private suspend fun mutate(transform: (List<PasswordEntry>) -> List<PasswordEntry>) =
        mutex.withLock {
            // A copy, because [lock] may run on another thread while the KDF
            // is grinding and would otherwise wipe the very bytes being
            // derived from. A write already in flight finishes under the
            // passphrase it started with.
            val (pass, epoch) = synchronized(stateLock) {
                val current = passphrase ?: throw MyPassException.Locked()
                current.copy() to lockEpoch
            }
            try {
                val entries = requireUnlocked()
                val updated = transform(entries)
                withContext(ioDispatcher) { writeVault(vaultFile, pass, updated) }
                synchronized(stateLock) {
                    // A lock while the write was in flight stays locked: the
                    // entries were written — the user's edit is not lost — but
                    // they do not come back on screen.
                    if (lockEpoch == epoch && _state.value is VaultState.Unlocked) {
                        lastAccessMillis = elapsedMillis()
                        _state.value = VaultState.Unlocked(updated)
                        scheduleExpiryLocked()
                    }
                }
            } finally {
                pass.close()
            }
        }

    // --- state machine, all of it under stateLock ------------------------

    private fun openLocked(pass: Passphrase, entries: List<PasswordEntry>) {
        passphrase?.close()
        passphrase = pass
        lastAccessMillis = elapsedMillis()
        _state.value = VaultState.Unlocked(entries)
        scheduleExpiryLocked()
    }

    private fun lockLocked() {
        passphrase?.close()
        passphrase = null
        lockEpoch++
        // Detach before cancelling: this may be running inside the expiry job
        // itself, which is allowed to cancel itself only because nothing
        // suspends afterwards.
        val pending = expiryJob
        expiryJob = null
        pending?.cancel()
        _state.value = VaultState.Locked
    }

    private fun expiredLocked(): Boolean {
        if (autoLock <= 0 || _state.value !is VaultState.Unlocked) return false
        return elapsedMillis() - lastAccessMillis >= autoLock * 60_000L
    }

    /**
     * Arm the timer that relocks the vault when the window runs out.
     *
     * Without this, auto-lock would only ever happen on the next call into
     * the repository — so a screen left open on a revealed password, which
     * asks nothing of the vault while it sits there, would keep it open
     * indefinitely.
     */
    private fun scheduleExpiryLocked() {
        val pending = expiryJob
        expiryJob = null
        pending?.cancel()
        if (autoLock <= 0 || _state.value !is VaultState.Unlocked) return
        val remaining = (lastAccessMillis + autoLock * 60_000L) - elapsedMillis()
        expiryJob = scope.launch {
            if (remaining > 0) delay(remaining)
            synchronized(stateLock) { if (expiredLocked()) lockLocked() }
        }
    }

    private fun writeVault(
        file: File,
        pass: Passphrase,
        entries: List<PasswordEntry>,
        replacingKey: Boolean = false,
    ) {
        val params = ScryptFormat.Params.DEFAULT.copy(logN = scryptLogN)
        var committed = false
        var replacementFailure: MyPassException.ReplacementCommitted? = null
        try {
            if (replacingKey) replacementWriter(file, pass, entries, params)
            else Vault.store(file, pass, entries, params)
            committed = replacingKey
        } catch (e: VaultException) {
            // A replacement may have committed before a durability error.
            // Do not let a subsequent edit write with the previous key.
            if (replacingKey && e is VaultException.Write && e.primaryCommitted) {
                committed = true
                lock()
                val failure = MyPassException.ReplacementCommitted(e)
                replacementFailure = failure
                throw failure
            }
            throw mapVaultException(file, e)
        } finally {
            if (committed) {
                synchronized(stateLock) { vaultGeneration++ }
                try {
                    onReplacementCommitted()
                } catch (e: Throwable) {
                    // Integration cleanup must not leave the old key usable
                    // or make a committed replacement look like a failed write.
                    lock()
                    val failure = replacementFailure
                    if (failure != null) {
                        failure.addSuppressed(e)
                        throw failure
                    }
                    throw MyPassException.ReplacementCommitted(e, durabilityUnconfirmed = false)
                }
            }
        }
    }

    private fun requireUnlocked(): List<PasswordEntry> =
        entriesOrNull() ?: throw MyPassException.Locked()

    private fun mapVaultException(file: File, e: VaultException): MyPassException = when {
        e is VaultException.Format &&
            e.cause is ScryptFormatException.WrongPassphrase -> MyPassException.WrongPassphrase()
        e is VaultException.ResourceLimit ||
            (e is VaultException.Format && e.cause is ScryptFormatException.ParamsTooLarge) -> MyPassException.ResourceLimit(e)
        e is VaultException.Read || e is VaultException.Write -> MyPassException.Io(e)
        else -> MyPassException.CorruptVault(file, e)
    }

    companion object {
        const val VAULT_FILE_NAME = "mypass.scrypt"
    }
}
