package nu.staldal.mypass.data

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.resolveValuePlaceholders
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import nu.staldal.mypass.vault.PasswordEntry
import nu.staldal.mypass.vault.Secret
import nu.staldal.mypass.vault.Vault
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Read-only conversion from a KeePass KDBX database to MyPass entries. */
object KeePassImport {
    const val MAX_FILE_BYTES = 32 * 1024 * 1024

    fun decode(input: InputStream, passphrase: String): List<PasswordEntry> {
        val data = readBounded(input)
        val credentials = Credentials.from(EncryptedValue.fromString(passphrase))
        val database = try {
            KeePassDatabase.decode(ByteArrayInputStream(data), credentials, kdfProvider = BoundedKdf)
        } catch (e: MyPassException) {
            throw e
        } catch (_: OutOfMemoryError) {
            throw MyPassException.InvalidInput("KeePass file", "needs too much memory to import")
        } catch (_: StackOverflowError) {
            throw MyPassException.InvalidInput("KeePass file", "is nested too deeply")
        } catch (e: Exception) {
            throw MyPassException.InvalidInput(
                "KeePass file",
                if (e.message.isNullOrBlank()) "could not decrypt or parse it"
                else Validation.displayText(e.message!!),
            )
        } finally {
            data.fill(0)
        }
        return entries(database)
    }

    /** Reject unreasonable parameters before allocating or starting a hostile KDF. */
    private object BoundedKdf : KdfProvider {
        private const val MAX_AES_ROUNDS = 100_000_000UL
        private const val MAX_ARGON_MEMORY = 67_108_864UL
        private const val MAX_ARGON_ITERATIONS = 100UL
        private const val MAX_ARGON_PARALLELISM = 16U

        override fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray =
            when (kdfParameters) {
                is KdfParameters.Aes -> aes(kdfParameters, compositeKey)
                is KdfParameters.Argon2 -> argon2(kdfParameters, compositeKey)
            }

        private fun aes(parameters: KdfParameters.Aes, compositeKey: ByteArray): ByteArray {
            if (parameters.rounds > MAX_AES_ROUNDS) resourceLimit()
            val transformed = compositeKey.copyOf()
            val seed = parameters.seed.toByteArray()
            try {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
                repeat(parameters.rounds.toInt()) {
                    cipher.update(transformed, 0, 16, transformed, 0)
                    cipher.update(transformed, 16, 16, transformed, 16)
                }
                return MessageDigest.getInstance("SHA-256").digest(transformed)
            } finally {
                transformed.fill(0)
                seed.fill(0)
            }
        }

        private fun argon2(parameters: KdfParameters.Argon2, compositeKey: ByteArray): ByteArray {
            if (parameters.memory > MAX_ARGON_MEMORY ||
                parameters.iterations > MAX_ARGON_ITERATIONS ||
                parameters.parallelism > MAX_ARGON_PARALLELISM
            ) resourceLimit()
            if (parameters.version != Argon2Parameters.ARGON2_VERSION_10.toUInt() &&
                parameters.version != Argon2Parameters.ARGON2_VERSION_13.toUInt()
            ) throw MyPassException.InvalidInput("KeePass file", "uses an unsupported Argon2 version")
            val type = when (parameters.variant) {
                KdfParameters.Argon2.Variant.Argon2d -> Argon2Parameters.ARGON2_d
                KdfParameters.Argon2.Variant.Argon2id -> Argon2Parameters.ARGON2_id
            }
            val builder = Argon2Parameters.Builder(type)
                .withVersion(parameters.version.toInt())
                .withSalt(parameters.salt.toByteArray())
                .withMemoryAsKB((parameters.memory / 1024UL).toInt())
                .withIterations(parameters.iterations.toInt())
                .withParallelism(parameters.parallelism.toInt())
            parameters.secretKey?.toByteArray()?.let(builder::withSecret)
            parameters.associatedData?.toByteArray()?.let(builder::withAdditional)
            return ByteArray(32).also { output ->
                Argon2BytesGenerator().apply { init(builder.build()) }
                    .generateBytes(compositeKey, output)
            }
        }

        private fun resourceLimit(): Nothing =
            throw MyPassException.InvalidInput("KeePass file", "requests excessive key-derivation resources")
    }

    internal fun entries(database: KeePassDatabase): List<PasswordEntry> {
        val recycleBin = database.content.meta.recycleBinUuid
            ?.takeIf { database.content.meta.recycleBinEnabled }
        val imported = mutableListOf<PasswordEntry>()
        val pending = ArrayDeque<Pair<Group, Int>>().apply { add(database.content.group to 1) }
        while (pending.isNotEmpty()) {
            val (group, depth) = pending.removeLast()
            if (group.uuid == recycleBin) continue
            if (depth > MAX_GROUP_DEPTH) {
                throw MyPassException.InvalidInput("KeePass file", "contains groups nested more than $MAX_GROUP_DEPTH levels")
            }
            for (entry in group.entries) {
                imported += convert(database, entry)
                if (imported.size > Vault.MAX_ENTRIES) {
                    throw MyPassException.InvalidInput(
                        "KeePass file", "contains more than ${Vault.MAX_ENTRIES} entries"
                    )
                }
            }
            group.groups.forEach { pending.add(it to depth + 1) }
        }
        val names = mutableSetOf<String>()
        imported.forEach { entry ->
            Validation.validateEntry(entry)
            if (!names.add(entry.name)) throw MyPassException.AlreadyExists(entry.name)
        }
        return imported
    }

    private fun convert(database: KeePassDatabase, entry: Entry): PasswordEntry {
        val name = entry.fields.title?.content.orEmpty()
        val username = entry.fields.userName?.content.orEmpty()
        val password = entry.fields.password
            ?.let { database.resolveValuePlaceholders(entry, it).content }
            .orEmpty()
        val url = entry.fields.url?.content?.trim()?.takeIf(String::isNotEmpty)
        return PasswordEntry(name, username, Secret(password), url)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_FILE_BYTES + 1 - out.size()))
                if (count < 0) break
                if (count == 0) throw IOException("provider made no read progress")
                if (out.size() + count > MAX_FILE_BYTES) {
                    throw MyPassException.InvalidInput("KeePass file", "is larger than $MAX_FILE_BYTES bytes")
                }
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } catch (e: IOException) {
            throw MyPassException.InvalidInput("KeePass file", "could not read it: ${e.message ?: "I/O error"}")
        } finally {
            buffer.fill(0)
        }
    }

    private const val MAX_GROUP_DEPTH = 64
}
