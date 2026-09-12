package nu.staldal.pw.vault

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A stored password. Redacted by [toString] and serialized transparently as a
 * JSON string, exactly like the desktop `pw`'s `Secret`.
 *
 * The only way the secret leaves the type is [expose] — named so that every
 * exposure site is greppable. Keep it that way.
 *
 * Unlike the Rust original this is **not** zeroized on drop: it holds a
 * [String], and neither ART nor the JVM lets a `String`'s backing array be
 * wiped (the JSON parser would have made its own copies anyway). pw-android
 * relies on process isolation and a short auto-lock instead; see the security
 * notes in README.md.
 */
@Serializable(with = SecretSerializer::class)
class Secret(private val value: String) {

    /** Named so that every place the secret leaves the type is greppable. */
    fun expose(): String = value

    val length: Int get() = value.length

    override fun toString(): String = "[redacted]"

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

internal object SecretSerializer : KSerializer<Secret> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("nu.staldal.pw.vault.Secret", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Secret) = encoder.encodeString(value.expose())

    override fun deserialize(decoder: Decoder): Secret = Secret(decoder.decodeString())
}
