package nu.staldal.mypass.data

import nu.staldal.mypass.crypto.Random
import nu.staldal.mypass.vault.Secret

/** Password generation, with the desktop `MyPass`'s defaults and limits. */
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
     * The charset is taken as unique Unicode code points, so an astral
     * character counts once and is never split. `SecureRandom.nextInt(bound)`
     * uses rejection sampling, so there is no modulo bias.
     */
    fun generate(length: Int, charset: String): Secret {
        if (length <= 0 || length > MAX_LENGTH) {
            throw MyPassException.InvalidInput(
                "password length",
                "must be between 1 and $MAX_LENGTH",
            )
        }
        val chars = charset.codePoints().toArray()
        val uniqueChars = chars.toSet()
        if (uniqueChars.size < 2) {
            throw MyPassException.InvalidInput(
                "password charset",
                "must contain at least 2 distinct characters",
            )
        }
        if (uniqueChars.size != chars.size) {
            throw MyPassException.InvalidInput(
                "password charset",
                "must not contain duplicate characters",
            )
        }
        val builder = StringBuilder(length)
        repeat(length) {
            builder.appendCodePoint(chars[Random.secureRandom.nextInt(chars.size)])
        }
        return Secret(builder.toString())
    }
}
