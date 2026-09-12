package nu.staldal.pw.vault

import java.io.File
import nu.staldal.pw.crypto.ScryptFormatException

/**
 * Failures of the [Vault] storage layer, mirroring the desktop `pw`'s
 * `vault::Error`. Errors are layered the same way there and here:
 * [ScryptFormatException] -> [VaultException] -> [nu.staldal.pw.data.PwException].
 */
sealed class VaultException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class Read(val file: File, cause: Throwable) :
        VaultException("cannot read $file: ${cause.message}", cause)

    class Write(val file: File, cause: Throwable) :
        VaultException("cannot write $file: ${cause.message}", cause)

    class Format(cause: ScryptFormatException) :
        VaultException(cause.message ?: "invalid file format", cause)

    class InvalidJson(cause: Throwable?) : VaultException("invalid vault content", cause)

    class UnsupportedVersion(val version: Int) : VaultException(
        "vault format version $version is newer than this version of pw understands"
    )
}
