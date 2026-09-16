package nu.staldal.pw.data

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KeePassImportTest {
    private val passphrase = "keepass passphrase"

    @Test
    fun decodesKdbx3AndMapsSupportedFields() {
        val entry = entry("Example", "alice", "secret", "https://example.com/login")
        val database = database(entry)
        val encoded = ByteArrayOutputStream().also(database::encode).toByteArray()

        val imported = KeePassImport.decode(ByteArrayInputStream(encoded), passphrase)

        assertEquals(1, imported.size)
        assertEquals("Example", imported.single().name)
        assertEquals("alice", imported.single().username)
        assertEquals("secret", imported.single().password.expose())
        assertEquals("https://example.com/login", imported.single().url)
        assertNull(imported.single().realm)
    }

    @Test
    fun decodesKdbx4WithArgon2id() {
        val credentials = Credentials.from(EncryptedValue.fromString(passphrase))
        val database = KeePassDatabase.Ver4x.create("Root", Meta(), credentials).let { original ->
            val parameters = original.header.kdfParameters as KdfParameters.Argon2
            original.copy(
                header = original.header.copy(
                    kdfParameters = parameters.copy(
                        variant = KdfParameters.Argon2.Variant.Argon2id,
                        salt = ByteArray(32) { it.toByte() }.toByteString(),
                        parallelism = 1U,
                        memory = 1024UL * 1024UL,
                        iterations = 1UL,
                    )
                ),
                content = original.content.copy(
                    group = original.content.group.copy(entries = listOf(entry("KDBX 4", password = "secret")))
                ),
            )
        }
        val encoded = ByteArrayOutputStream().also(database::encode).toByteArray()

        val imported = KeePassImport.decode(ByteArrayInputStream(encoded), passphrase)

        assertEquals("KDBX 4", imported.single().name)
        assertEquals("secret", imported.single().password.expose())
    }

    @Test
    fun wrongPassphraseAndDuplicateTitlesAreRejected() {
        val database = database(entry("Same"), entry("Same"))
        val encoded = ByteArrayOutputStream().also(database::encode).toByteArray()

        assertThrows(PwException.InvalidInput::class.java) {
            KeePassImport.decode(ByteArrayInputStream(encoded), "wrong")
        }
        assertThrows(PwException.AlreadyExists::class.java) {
            KeePassImport.decode(ByteArrayInputStream(encoded), passphrase)
        }
    }

    @Test
    fun recycleBinEntriesAreSkipped() {
        val recycleId = UUID.randomUUID()
        val database = database(entry("Live")).let { original ->
            original.copy(
                content = original.content.copy(
                    meta = original.content.meta.copy(recycleBinEnabled = true, recycleBinUuid = recycleId),
                    group = original.content.group.copy(
                        groups = listOf(Group(recycleId, "Recycle Bin", entries = listOf(entry("Deleted"))))
                    ),
                )
            )
        }

        assertEquals(listOf("Live"), KeePassImport.entries(database).map { it.name })
    }

    @Test
    fun providerThatMakesNoProgressIsRejected() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }

        assertThrows(PwException.InvalidInput::class.java) {
            KeePassImport.decode(stalled, passphrase)
        }
    }

    @Test
    fun passwordReferencesResolveOnlyInsideSecret() {
        val reference = "{REF:P@T:Other}"
        val database = database(
            entry("Other", password = "referenced secret"),
            entry(reference, password = reference),
        )

        val imported = KeePassImport.entries(database)
        val referenced = imported.single { it.name == reference }

        assertEquals(reference, referenced.name)
        assertEquals("referenced secret", referenced.password.expose())
    }

    private fun database(vararg entries: Entry): KeePassDatabase.Ver3x {
        val credentials = Credentials.from(EncryptedValue.fromString(passphrase))
        return KeePassDatabase.Ver3x.create("Root", Meta(), credentials).let { database ->
            database.copy(content = database.content.copy(group = database.content.group.copy(entries = entries.toList())))
        }
    }

    private fun entry(
        title: String,
        username: String = "",
        password: String = "",
        url: String = "",
    ) = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.of(
            "Title" to EntryValue.Plain(title),
            "UserName" to EntryValue.Plain(username),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password)),
            "URL" to EntryValue.Plain(url),
            "Notes" to EntryValue.Plain("not imported"),
        ),
    )
}
