package nu.staldal.pw.crypto

import java.security.SecureRandom

/**
 * The single cryptographically secure random source. `SecureRandom`'s
 * no-argument constructor is seeded by the OS on Android, and the instance is
 * thread-safe, so one shared instance is both correct and cheaper than
 * re-seeding per call.
 */
object Random {
    val secureRandom: SecureRandom by lazy { SecureRandom() }
}
