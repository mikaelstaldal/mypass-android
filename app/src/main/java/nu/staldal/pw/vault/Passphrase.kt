package nu.staldal.pw.vault

import java.io.Closeable

/**
 * The master passphrase, held as UTF-8 bytes so it can be wiped with
 * [close]. Redacted by [toString].
 *
 * The caveat the desktop `pw` does not have: the passphrase reaches this class
 * as a `String` from a Compose text field, and that `String` cannot be wiped.
 * Wiping what we can still shortens the window for the derived-key inputs, so
 * it is worth doing, but it is not the guarantee `Zeroizing<String>` gives in
 * Rust. See the security notes in README.md.
 */
class Passphrase(private val bytes: ByteArray) : Closeable {

    constructor(passphrase: String) : this(passphrase.toByteArray(Charsets.UTF_8))

    /** Named so that every place the passphrase leaves the type is greppable. */
    fun expose(): ByteArray = bytes

    val isEmpty: Boolean get() = bytes.isEmpty()

    /** A copy that can be wiped independently of this one. */
    fun copy(): Passphrase = Passphrase(bytes.copyOf())

    override fun close() = bytes.fill(0)

    override fun toString(): String = "Passphrase([redacted])"
}
