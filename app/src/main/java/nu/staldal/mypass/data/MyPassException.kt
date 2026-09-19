package nu.staldal.mypass.data

import java.io.File
import nu.staldal.mypass.vault.VaultException

/**
 * Domain-level failures, the top of the three-layer error stack
 * (`ScryptFormatException` -> `VaultException` -> `MyPassException`). Each carries
 * a message the UI can show as-is.
 */
sealed class MyPassException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class ResourceLimit(cause: VaultException) : MyPassException(cause.message ?: "vault resource limit exceeded", cause)

    class NoVault(val file: File) : MyPassException("no vault at $file")

    class VaultAlreadyExists(val file: File) : MyPassException("vault $file already exists")

    class WrongPassphrase : MyPassException("incorrect passphrase")

    class NotFound(val name: String) : MyPassException("no entry '${Validation.displayText(name)}' in the vault")

    class AlreadyExists(val name: String) : MyPassException("an entry named '${Validation.displayText(name)}' already exists")

    class InvalidInput(val what: String, val reason: String) :
        MyPassException("invalid $what: $reason")

    class Io(cause: VaultException) : MyPassException(cause.message ?: "I/O error", cause)

    class ReplacementCommitted(cause: Throwable?, durabilityUnconfirmed: Boolean = true) : MyPassException(
        "The vault was replaced and uses the incoming passphrase. " +
            (if (durabilityUnconfirmed) "Durability could not be confirmed; unlock with that passphrase and retry."
             else "The vault remains locked; unlock with that passphrase."),
        cause,
    )

    class CorruptVault(val file: File, cause: VaultException) :
        MyPassException("cannot use vault $file: ${cause.message}", cause)

    class Locked : MyPassException("the vault is locked")
}
