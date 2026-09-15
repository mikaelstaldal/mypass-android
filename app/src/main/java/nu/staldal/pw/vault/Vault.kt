package nu.staldal.pw.vault

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import nu.staldal.pw.crypto.ScryptFormat
import nu.staldal.pw.crypto.ScryptFormatException

/**
 * Encrypted vault storage: the JSON envelope inside the scrypt format, with
 * atomic writes and owner-only permissions.
 *
 * This object never prompts: the passphrase enters as a [Passphrase]
 * parameter. [load] is strictly read-only.
 */
object Vault {

    /**
     * Version of the JSON envelope inside the encrypted file. Bare arrays
     * written by pw <= 0.1.x are still accepted on read.
     */
    const val ENVELOPE_VERSION = 1

    /**
     * `encodeDefaults = false` is what keeps `url` and `realm` out of the JSON
     * when they are absent, so url-less entries stay byte-identical to what
     * the desktop `pw` writes. `name`, `username` and `password` have no
     * defaults and are therefore always written.
     */
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    // Small enough to bound the ciphertext, plaintext, JSON tree and domain copies.
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_FIELD_CHARS = 16_384
    const val MAX_DEPTH = 8
    private const val ENTRY_FIELD_COUNT = 5 // name, username, password, url, realm
    const val MAX_JSON_TOKENS = MAX_ENTRIES * (2 * ENTRY_FIELD_COUNT + 1) + 16

    /** Reads at most the limit plus one byte, even from an endless provider. */
    fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_FILE_BYTES + 1 - out.size()))
            if (count < 0) return out.toByteArray()
            if (count == 0) throw IOException("provider made no read progress")
            if (out.size() + count > MAX_FILE_BYTES) throw VaultException.ResourceLimit()
            out.write(buffer, 0, count)
        }
    }

    /** Scan before recursive JSON parsing; escaped quotes do not end strings. */
    private fun checkContentBudget(text: String) {
        if (text.length > MAX_FILE_BYTES - ScryptFormat.OVERHEAD) throw VaultException.ResourceLimit()
        var depth = 0
        var quoted = false
        var escaped = false
        var fieldLength = 0
        var objects = 0
        var tokens = 0
        var primitive = false
        fun token() {
            if (++tokens > MAX_JSON_TOKENS) throw VaultException.ResourceLimit()
        }
        for (ch in text) {
            if (quoted) {
                if (++fieldLength > MAX_FIELD_CHARS) throw VaultException.ResourceLimit()
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> { token(); quoted = true; fieldLength = 0; primitive = false }
                '{', '[' -> {
                    token()
                    primitive = false
                    if (++depth > MAX_DEPTH) throw VaultException.ResourceLimit()
                    if (ch == '{' && ++objects > MAX_ENTRIES + 1) throw VaultException.ResourceLimit()
                }
                '}', ']' -> {
                    if (--depth < 0) throw VaultException.InvalidJson(null)
                    primitive = false
                }
                ',', ':', ' ', '\t', '\r', '\n' -> primitive = false
                else -> if (!primitive) { token(); primitive = true }
            }
        }
    }

    /** Serialize entries to the JSON envelope — exactly what [store] encrypts. */
    fun toJson(entries: List<PasswordEntry>): String {
        if (entries.size > MAX_ENTRIES) throw VaultException.ResourceLimit()
        entries.forEach { entry ->
            if (listOf(entry.name, entry.username, entry.password.expose(), entry.url, entry.realm)
                    .any { it != null && it.length >= MAX_FIELD_CHARS }) throw VaultException.ResourceLimit()
        }
        return json.encodeToString(Envelope.serializer(), Envelope(ENVELOPE_VERSION, entries))
            .also { checkContentBudget(it)
                val encoded = it.toByteArray(Charsets.UTF_8)
                try {
                    if (encoded.size > MAX_FILE_BYTES - ScryptFormat.OVERHEAD)
                        throw VaultException.ResourceLimit()
                } finally {
                    encoded.fill(0)
                }
            }
    }

    @kotlinx.serialization.Serializable
    private data class Envelope(val version: Int, val entries: List<PasswordEntry>)

    /**
     * Decrypt and parse the vault. Read-only: never creates or touches the
     * file.
     */
    fun load(file: File, passphrase: Passphrase): List<PasswordEntry> {
        val data = try {
            file.inputStream().use(::readBounded)
        } catch (e: IOException) {
            throw VaultException.Read(file, e)
        }
        val plaintext = try {
            ScryptFormat.decrypt(data, passphrase.expose())
        } catch (e: ScryptFormatException) {
            throw VaultException.Format(e)
        }
        try {
            return parse(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Parse the decrypted envelope. A bare array is what pw <= 0.1.x wrote;
     * it is read here and upgraded to the envelope on the next [store].
     *
     * On failure the decrypted text is deliberately kept out of the error.
     */
    internal fun parse(text: String): List<PasswordEntry> {
        checkContentBudget(text)
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw VaultException.InvalidJson(e)
        }
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val version = try {
                    root["version"]?.jsonPrimitive?.int
                } catch (e: Exception) {
                    throw VaultException.InvalidJson(e)
                } ?: throw VaultException.InvalidJson(null)
                if (version != ENVELOPE_VERSION) throw VaultException.UnsupportedVersion(version)
                root["entries"] as? JsonArray ?: throw VaultException.InvalidJson(null)
            }
            else -> throw VaultException.InvalidJson(null)
        }
        if (entries.size > MAX_ENTRIES) throw VaultException.ResourceLimit()
        return try {
            entries.map { json.decodeFromJsonElement(PasswordEntry.serializer(), it) }
        } catch (e: Exception) {
            throw VaultException.InvalidJson(e)
        }
    }

    /**
     * Encrypt and write the vault atomically.
     *
     * The ciphertext goes to `<file>.tmp` (owner-only) which is fsynced and
     * then renamed over the target; an existing vault is first copied to
     * `<file>.bak`. A crash at any point leaves the target as either the
     * complete old or the complete new vault, never truncated.
     *
     * The temporary file lives next to the target — the rename may not cross
     * filesystems — and its name is deterministic, so the same three paths
     * (`<file>`, `<file>.tmp`, `<file>.bak`) are all that is ever touched.
     *
     * Unlike [storeReplacingKey], this ordinary write path does not fsync
     * the directory after the rename. A power loss in the moment between the rename
     * and the filesystem's own flush can therefore still lose the rename — but
     * not corrupt anything: what survives is the complete old vault.
     */
    fun store(
        file: File,
        passphrase: Passphrase,
        entries: List<PasswordEntry>,
        params: ScryptFormat.Params,
    ) {
        val plaintext = toJson(entries).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            ScryptFormat.encrypt(plaintext, passphrase.expose(), params)
        } catch (e: ScryptFormatException) {
            throw VaultException.Format(e)
        } finally {
            plaintext.fill(0)
        }

        val tmp = tempFile(file)
        try {
            // Remove a stale temp file from a crashed previous run.
            tmp.delete()
            if (!tmp.createNewFile()) throw IOException("cannot create $tmp")
            restrictPermissions(tmp)
            FileOutputStream(tmp).use { out ->
                out.write(ciphertext)
                out.flush()
                out.fd.sync()
            }

            if (file.exists()) {
                val bak = backupFile(file)
                file.copyTo(bak, overwrite = true)
                restrictPermissions(bak)
            }

            if (!tmp.renameTo(file)) throw IOException("cannot rename $tmp to $file")
            restrictPermissions(file)
        } catch (e: IOException) {
            tmp.delete()
            throw VaultException.Write(file, e)
        } catch (e: SecurityException) {
            tmp.delete()
            throw VaultException.Write(file, IOException(e))
        }
    }

    /**
     * Rotation/import policy: retain a snapshot of the incoming vault, never
     * ciphertext protected by the previous key. Commit the backup first, so
     * an interruption always leaves the old primary or the complete new one.
     * Both renames are followed by a directory fsync before reporting success.
     */
    fun storeReplacingKey(
        file: File,
        passphrase: Passphrase,
        entries: List<PasswordEntry>,
        params: ScryptFormat.Params,
    ) = storeReplacingKey(file, passphrase, entries, params) {}

    internal enum class ReplacementStep {
        TEMP_SYNCED, BACKUP_RENAMED, BACKUP_SYNCED,
        PRIMARY_TEMP_SYNCED, PRIMARY_RENAMED, PRIMARY_SYNCED,
    }

    // The callback lets JVM tests interrupt each commit boundary without
    // replacing real filesystem operations with mocks.
    internal fun storeReplacingKey(
        file: File,
        passphrase: Passphrase,
        entries: List<PasswordEntry>,
        params: ScryptFormat.Params,
        afterStep: (ReplacementStep) -> Unit,
    ) {
        val plaintext = toJson(entries).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            ScryptFormat.encrypt(plaintext, passphrase.expose(), params)
        } catch (e: ScryptFormatException) {
            throw VaultException.Format(e)
        } finally {
            plaintext.fill(0)
        }
        val tmp = tempFile(file)
        val bak = backupFile(file)
        var primaryCommitted = false
        fun stage() {
            if (tmp.exists() && !tmp.delete()) throw IOException("cannot remove $tmp")
            if (!tmp.createNewFile()) throw IOException("cannot create $tmp")
            restrictPermissions(tmp)
            FileOutputStream(tmp).use { out ->
                out.write(ciphertext)
                out.fd.sync()
            }
        }
        fun syncDirectory() {
            FileChannel.open(
                (file.absoluteFile.parentFile ?: throw IOException("missing vault directory")).toPath(),
                StandardOpenOption.READ,
            ).use {
                it.force(true)
            }
        }
        try {
            stage()
            afterStep(ReplacementStep.TEMP_SYNCED)
            if (!tmp.renameTo(bak)) throw IOException("cannot rename $tmp to $bak")
            afterStep(ReplacementStep.BACKUP_RENAMED)
            syncDirectory()
            afterStep(ReplacementStep.BACKUP_SYNCED)
            stage()
            afterStep(ReplacementStep.PRIMARY_TEMP_SYNCED)
            if (!tmp.renameTo(file)) throw IOException("cannot rename $tmp to $file")
            primaryCommitted = true
            afterStep(ReplacementStep.PRIMARY_RENAMED)
            syncDirectory()
            afterStep(ReplacementStep.PRIMARY_SYNCED)
        } catch (e: IOException) {
            tmp.delete()
            throw VaultException.Write(file, e, primaryCommitted)
        } catch (e: SecurityException) {
            tmp.delete()
            throw VaultException.Write(file, IOException(e), primaryCommitted)
        }
    }

    /**
     * Narrow the file to owner-only, best effort.
     *
     * The vault lives in the app's private storage, which Android already
     * makes unreachable by other apps — that, not this, is what keeps it
     * private. `File.setReadable`/`setWritable` report failure by returning
     * `false`, and a filesystem that refuses them is not an error here
     * precisely because nothing depends on them succeeding. Do not move the
     * vault somewhere world-readable and expect these calls to save it.
     */
    private fun restrictPermissions(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    /** `<file>.tmp` next to the vault, e.g. `pw.scrypt` -> `pw.scrypt.tmp`. */
    fun tempFile(file: File): File = File(file.path + ".tmp")

    /** `<file>.bak` next to the vault, e.g. `pw.scrypt` -> `pw.scrypt.bak`. */
    fun backupFile(file: File): File = File(file.path + ".bak")
}
