package nu.staldal.pw.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nu.staldal.pw.crypto.ScryptFormat
import nu.staldal.pw.crypto.ScryptFormatException
import nu.staldal.pw.vault.PasswordEntry
import nu.staldal.pw.vault.Passphrase
import nu.staldal.pw.vault.Vault
import nu.staldal.pw.vault.VaultException

/** Whether the vault is open, and what is in it when it is. */
sealed interface VaultState {
    data object Locked : VaultState
    data class Unlocked(val entries: List<PasswordEntry>) : VaultState
}

/**
 * The domain operations of desktop `pw` — init, get, list, add, update,
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
class PwRepository(
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
        if (file.exists()) throw PwException.VaultAlreadyExists(file)
        vaultDir.mkdirs()
        openAfter(Passphrase(newPassphrase)) { pass ->
            withContext(ioDispatcher) { writeVault(file, pass, emptyList()) }
            emptyList()
        }
    }

    /** Decrypt the vault and hold it open. */
    suspend fun unlock(enteredPassphrase: String) = mutex.withLock {
        val file = vaultFile
        if (!file.exists()) throw PwException.NoVault(file)
        openAfter(Passphrase(enteredPassphrase)) { pass ->
            try {
                withContext(ioDispatcher) { Vault.load(file, pass) }
            } catch (e: VaultException) {
                throw mapVaultException(file, e)
            }
        }
    }

    /** Look up the entry named [name] in the open vault. */
    fun get(name: String): PasswordEntry =
        requireUnlocked().find { it.name == name } ?: throw PwException.NotFound(name)

    /** Add a new entry. Fails if an entry with the same name exists. */
    suspend fun add(entry: PasswordEntry) = mutate { entries ->
        Validation.validateEntry(entry)
        if (entries.any { it.name == entry.name }) throw PwException.AlreadyExists(entry.name)
        entries + entry
    }

    /**
     * Replace the entry named [name]. The replacement may be named something
     * else, which is a rename — refused if the new name is taken.
     */
    suspend fun update(name: String, entry: PasswordEntry) = mutate { entries ->
        Validation.validateEntry(entry)
        val index = entries.indexOfFirst { it.name == name }
        if (index < 0) throw PwException.NotFound(name)
        if (entry.name != name && entries.any { it.name == entry.name }) {
            throw PwException.AlreadyExists(entry.name)
        }
        entries.toMutableList().apply { set(index, entry) }
    }

    /** Remove the entry named [name]. */
    suspend fun remove(name: String) = mutate { entries ->
        if (entries.none { it.name == name }) throw PwException.NotFound(name)
        entries.filter { it.name != name }
    }

    /**
     * Re-encrypt the vault under a new passphrase. The entries are unchanged;
     * only the KDF salt, derived keys and ciphertext are.
     */
    suspend fun changePassphrase(newPassphrase: String) = mutex.withLock {
        val entries = requireUnlocked()
        openAfter(Passphrase(newPassphrase)) { pass ->
            withContext(ioDispatcher) { writeVault(vaultFile, pass, entries) }
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
        if (!file.exists()) throw PwException.NoVault(file)
        try {
            file.inputStream().use { it.copyTo(out) }
        } catch (e: IOException) {
            throw PwException.Io(VaultException.Read(file, e))
        }
    }

    /**
     * Replace the vault with an scrypt-format file from elsewhere — typically
     * a `pw.scrypt` copied off a desktop.
     *
     * The import is verified before it lands: the bytes must parse as the
     * scrypt format and decrypt to a usable vault under [importPassphrase], so
     * a mistyped passphrase or a truncated copy is refused while the existing
     * vault is still there. It is then written through the ordinary atomic
     * path, which keeps the previous vault as `pw.scrypt.bak`.
     */
    suspend fun importVault(input: InputStream, importPassphrase: String) = mutex.withLock {
        val data = try {
            input.readBytes()
        } catch (e: IOException) {
            throw PwException.Io(VaultException.Read(vaultFile, e))
        }
        openAfter(Passphrase(importPassphrase)) { pass ->
            val entries = try {
                val plaintext = withContext(ioDispatcher) {
                    ScryptFormat.decrypt(data, pass.expose())
                }
                try {
                    Vault.parse(plaintext.toString(Charsets.UTF_8))
                } finally {
                    plaintext.fill(0)
                }
            } catch (e: ScryptFormatException) {
                throw mapVaultException(vaultFile, VaultException.Format(e))
            } catch (e: VaultException) {
                throw mapVaultException(vaultFile, e)
            }
            vaultDir.mkdirs()
            // Re-encrypt rather than copying the bytes, so the vault on this
            // device always uses this device's configured KDF cost.
            withContext(ioDispatcher) { writeVault(vaultFile, pass, entries) }
            entries
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
        produce: suspend (Passphrase) -> List<PasswordEntry>,
    ) {
        var owned = true
        try {
            val epoch = synchronized(stateLock) { lockEpoch }
            val entries = produce(pass)
            synchronized(stateLock) {
                if (lockEpoch != epoch) throw PwException.Locked()
                openLocked(pass, entries)
                owned = false
            }
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
                val current = passphrase ?: throw PwException.Locked()
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

    private fun writeVault(file: File, pass: Passphrase, entries: List<PasswordEntry>) {
        val params = ScryptFormat.Params.DEFAULT.copy(logN = scryptLogN)
        try {
            Vault.store(file, pass, entries, params)
        } catch (e: VaultException) {
            throw mapVaultException(file, e)
        }
    }

    private fun requireUnlocked(): List<PasswordEntry> =
        entriesOrNull() ?: throw PwException.Locked()

    private fun mapVaultException(file: File, e: VaultException): PwException = when {
        e is VaultException.Format &&
            e.cause is ScryptFormatException.WrongPassphrase -> PwException.WrongPassphrase()
        e is VaultException.Read || e is VaultException.Write -> PwException.Io(e)
        else -> PwException.CorruptVault(file, e)
    }

    companion object {
        const val VAULT_FILE_NAME = "pw.scrypt"
    }
}
