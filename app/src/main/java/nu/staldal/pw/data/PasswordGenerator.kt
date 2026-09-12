package nu.staldal.pw.data

import nu.staldal.pw.crypto.Random
import nu.staldal.pw.vault.Secret

/** Password generation, with the desktop `pw`'s defaults and limits. */
object PasswordGenerator {

    const val DEFAULT_LENGTH = 16
    const val DEFAULT_CHARSET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-"

    /** Longest password [generate] will produce. */
    const val MAX_LENGTH = 1024

    /**
     * Generate a random password of [length] characters from [charset], using
     * a cryptographically secure generator.
     *
     * The charset is taken as Unicode code points, so an astral character
     * counts once and is never split. `SecureRandom.nextInt(bound)` uses
     * rejection sampling, so there is no modulo bias.
     */
    fun generate(length: Int, charset: String): Secret {
        if (length <= 0 || length > MAX_LENGTH) {
            throw PwException.InvalidInput(
                "password length",
                "must be between 1 and $MAX_LENGTH",
            )
        }
        val chars = charset.codePoints().toArray()
        if (chars.toSet().size < 2) {
            throw PwException.InvalidInput(
                "password charset",
                "must contain at least 2 distinct characters",
            )
        }
        val builder = StringBuilder(length)
        repeat(length) {
            builder.appendCodePoint(chars[Random.secureRandom.nextInt(chars.size)])
        }
        return Secret(builder.toString())
    }
}
