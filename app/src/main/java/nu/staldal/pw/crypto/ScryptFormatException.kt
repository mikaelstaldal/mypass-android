package nu.staldal.pw.crypto

/**
 * Failures of the [ScryptFormat] codec, mirroring the desktop `pw`'s
 * `scrypt_format::Error` one for one. The layers above map these to
 * user-meaningful errors, so keep wrong-passphrase, wrong-file-type and
 * corrupt-file distinct.
 */
sealed class ScryptFormatException(message: String) : Exception(message) {
    class NotScryptFormat : ScryptFormatException("not an scrypt-encrypted file")

    class UnsupportedVersion(val version: Int) :
        ScryptFormatException("unsupported scrypt format version $version")

    class Truncated : ScryptFormatException("file is truncated")

    class InvalidParams(val logN: Int, val r: Long, val p: Long) :
        ScryptFormatException("invalid scrypt parameters (log2(N)=$logN, r=$r, p=$p)")

    class ParamsTooLarge(val logN: Int, val r: Long) : ScryptFormatException(
        "scrypt parameters exceed Android memory or work limits " +
            "(log2(N)=$logN, r=$r). Re-encrypt on desktop with --logN 16 -r 8 -p 1, or lower logN for a smaller phone."
    )

    class WrongPassphrase : ScryptFormatException("incorrect passphrase")

    class Corrupt : ScryptFormatException("file is corrupt: integrity check failed")
}
