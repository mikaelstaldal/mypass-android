package nu.staldal.pw.data

import java.io.File
import nu.staldal.pw.vault.VaultException

/**
 * Domain-level failures, the top of the three-layer error stack
 * (`ScryptFormatException` -> `VaultException` -> `PwException`). Each carries
 * a message the UI can show as-is.
 */
sealed class PwException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class NoVault(val file: File) : PwException("no vault at $file")

    class VaultAlreadyExists(val file: File) : PwException("vault $file already exists")

    class WrongPassphrase : PwException("incorrect passphrase")

    class NotFound(val name: String) : PwException("no entry '$name' in the vault")

    class AlreadyExists(val name: String) : PwException("an entry named '$name' already exists")

    class InvalidInput(val what: String, val reason: String) :
        PwException("invalid $what: $reason")

    class Io(cause: VaultException) : PwException(cause.message ?: "I/O error", cause)

    class ReplacementCommitted(cause: Throwable?, durabilityUnconfirmed: Boolean = true) : PwException(
        "The vault was replaced and uses the incoming passphrase. " +
            (if (durabilityUnconfirmed) "Durability could not be confirmed; unlock with that passphrase and retry."
             else "The vault remains locked; unlock with that passphrase."),
        cause,
    )

    class CorruptVault(val file: File, cause: VaultException) :
        PwException("cannot use vault $file: ${cause.message}", cause)

    class Locked : PwException("the vault is locked")
}
