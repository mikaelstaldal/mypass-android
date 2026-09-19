package nu.staldal.mypass.vault

import java.io.File
import nu.staldal.mypass.crypto.ScryptFormatException

/**
 * Failures of the [Vault] storage layer, mirroring the desktop `MyPass`'s
 * `vault::Error`. Errors are layered the same way there and here:
 * [ScryptFormatException] -> [VaultException] -> [nu.staldal.mypass.data.MyPassException].
 */
sealed class VaultException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class ResourceLimit : VaultException("vault exceeds Android size or content limits")

    class Read(val file: File, cause: Throwable) :
        VaultException("cannot read $file: ${cause.message}", cause)

    class Write(val file: File, cause: Throwable, val primaryCommitted: Boolean = false) :
        VaultException("cannot write $file: ${cause.message}", cause)

    class Format(cause: ScryptFormatException) :
        VaultException(cause.message ?: "invalid file format", cause)

    class InvalidJson(cause: Throwable?) : VaultException("invalid vault content", cause)

    class UnsupportedVersion(val version: Int) : VaultException(
        "vault format version $version is newer than this version of MyPass understands"
    )
}
