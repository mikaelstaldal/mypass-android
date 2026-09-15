package nu.staldal.pw.autofill

import java.security.MessageDigest

/**
 * Android does not authenticate an app's claimed webDomain. Package uniqueness
 * does not authenticate its publisher either: a sideloaded replacement can use
 * an absent browser's name. Only pinned or deliberately enrolled publishers may
 * claim websites. Enrollment grants authority to claim ANY website, not just
 * the publisher's own domain. Pins never enter the interoperable vault envelope.
 */
object Browsers {
    private val DIGEST = Regex("[0-9A-F]{64}")
    private val PACKAGE = Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")

    fun validPackageName(value: String): Boolean = value.matches(PACKAGE)

    fun certificateDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    // Verified 2026-09-15. Mozilla channel documentation plus Google's
    // credential-browser allowlist (release builds only):
    // https://firefox-source-docs.mozilla.org/mobile/android/fenix/certificates.html
    // https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json
    // Exclude Samsung C8A2...2AB8 even though Google labels it release:
    // Samsung Account (com.osp.app.signin) annotates its fingerprint list
    // "debug,platform,R_platformkey", positionally marking C8A2... as debug
    // and 34DF... as a platform key. This is an inference from a free-form
    // comment, not a browser-specific build label; err on the side of exclusion.
    // https://account.samsung.com/.well-known/assetlinks.json
    // Offline upstream excerpt: app/src/test/resources/browser-publishers.json
    val KNOWN: Map<String, Set<String>> = mapOf(
        "org.mozilla.firefox" to setOf("5004779088E7F988D5BC5CC5F8798FEBF4F8CD084A1B2A46EFD4C8EE4AEAF211"),
        "org.mozilla.firefox_beta" to setOf("F562C08F30778686D2A47B858F45E9EF357083085CB2891A96C409F360E9CAB9"),
        "org.mozilla.fenix" to setOf("77EAC4CEED36AFEFBA76179931DD4CC195AB0CD54BAF355D215E7BBDD28E402A"),
        "com.android.chrome" to setOf("F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"),
        "com.chrome.beta" to setOf(
            "DA633D34B69E63AE2103B49D53CE052FC5F7F3C53AAB94FDC2A208BDFD14249C",
            "3D7A1223019AA39D9EA0E3436AB7C0896BFB4FB679F4DE5FE7C23F326C8F994A",
        ),
        "com.chrome.dev" to setOf(
            "9044EE5FEE4BBC5E21DD44665431C4EB1F1F71A32716A0BC927BCBB39233CABF",
            "3D7A1223019AA39D9EA0E3436AB7C0896BFB4FB679F4DE5FE7C23F326C8F994A",
        ),
        "com.chrome.canary" to setOf("2019DFA1FB23EFBF70C5BCD1443C5BEAB04F3F2FF4366E9AC1E3457639A24CFC"),
        "com.sec.android.app.sbrowser" to setOf("34DF0E7A9F1CF1892E45C056B4973CD81CCF148A4050D11AEA4AC5A65F900A42"),
        "com.sec.android.app.sbrowser.beta" to setOf("34DF0E7A9F1CF1892E45C056B4973CD81CCF148A4050D11AEA4AC5A65F900A42"),
    )

    data class Identity(val current: Set<String>, val history: Set<String>)

    fun isTrustedBrowser(packageName: String?, identity: Identity?, enrolled: String): Boolean {
        if (packageName.isNullOrBlank() || identity == null || identity.current.isEmpty()) return false
        // Defense in depth for callers other than the Android certificate reader.
        if (!(identity.current + identity.history).all { it.matches(DIGEST) }) return false
        val pins = KNOWN[packageName].orEmpty() + parseEnrollments(enrolled)[packageName].orEmpty()
        // Android verifies single-signer rotation history. Multiple signers have
        // no rotation history: every current signer must be explicitly pinned.
        return if (identity.current.size == 1) {
            (identity.current + identity.history).any { it in pins }
        } else {
            identity.current.all { it in pins }
        }
    }

    /** Versioned settings use one package=digest,digest line per enrollment. */
    fun parseEnrollments(value: String): Map<String, Set<String>> = value.lines().mapNotNull { line ->
        val parts = line.split('=')
        if (parts.size != 2 || !parts[0].matches(PACKAGE)) return@mapNotNull null
        val pins = parts[1].split(',').toSet()
        if (!pins.all { it.matches(DIGEST) }) return@mapNotNull null
        parts[0] to pins
    }.toMap()

    fun enroll(value: String, packageName: String, identity: Identity): String {
        require(packageName.matches(PACKAGE)) { "Invalid package name" }
        require(identity.current.isNotEmpty() && identity.current.all { it.matches(DIGEST) }) {
            "Invalid signing certificate"
        }
        return (parseEnrollments(value) + (packageName to identity.current)).entries
            .sortedBy { it.key }.joinToString("\n") { (pkg, pins) -> "$pkg=${pins.sorted().joinToString(",")}" }

    }

    fun remove(value: String, packageName: String): String =
        (parseEnrollments(value) - packageName).entries
            .sortedBy { it.key }.joinToString("\n") { (pkg, pins) -> "$pkg=${pins.sorted().joinToString(",")}" }
}
