package nu.staldal.pw.autofill

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class BrowsersTest {
    private val evil = "A".repeat(64)
    private val next = "B".repeat(64)
    private fun identity(key: String, history: Set<String> = emptySet()) = Browsers.Identity(setOf(key), history)

    @Test fun officialChannelsRequireTheirOwnPublisher() {
        Browsers.KNOWN.forEach { (pkg, pins) ->
            pins.forEach { assertTrue(Browsers.isTrustedBrowser(pkg, identity(it), "")) }
            assertFalse(Browsers.isTrustedBrowser(pkg, identity(evil), ""))
        }
        val release = Browsers.KNOWN.getValue("org.mozilla.firefox").single()
        assertFalse(Browsers.isTrustedBrowser("org.mozilla.firefox_beta", identity(release), ""))
    }

    @Test fun missingCertificatesAndLegacyPackageNamesFailClosed() {
        assertFalse(Browsers.isTrustedBrowser("org.mozilla.firefox", null, "org.mozilla.firefox"))
        assertFalse(Browsers.isTrustedBrowser(null, identity(evil), ""))
        assertFalse(Browsers.isTrustedBrowser("com.android.chrome", identity(evil), "com.android.chrome"))
        assertFalse(Browsers.isTrustedBrowser("org.mozilla.firefox", Browsers.Identity(emptySet(), setOf(evil)), ""))
    }

    @Test fun enrollmentBindsPackageAndCertificateAndCanBeRevoked() {
        val pins = Browsers.enroll("", "com.example.browser", identity(evil))
        assertTrue(Browsers.isTrustedBrowser("com.example.browser", identity(evil), pins))
        assertFalse(Browsers.isTrustedBrowser("com.example.other", identity(evil), pins))
        assertFalse(Browsers.isTrustedBrowser("com.example.browser", identity(next), pins))
        assertFalse(Browsers.isTrustedBrowser("com.example.browser", identity(evil), Browsers.remove(pins, "com.example.browser")))
    }

    @Test fun rejectionIsBoundToPackageAndCurrentSigningIdentity() {
        val rejected = Browsers.reject("", "com.example.browser", identity(evil))
        assertTrue(Browsers.isRejected("com.example.browser", identity(evil), rejected))
        assertFalse(Browsers.isRejected("com.example.other", identity(evil), rejected))
        assertFalse(Browsers.isRejected("com.example.browser", identity(next), rejected))
        assertFalse(Browsers.isRejected("com.example.browser", identity(next, setOf(evil)), rejected))
    }

    @Test fun verifiedSingleSignerRotationIsAccepted() {
        val original = Browsers.KNOWN.getValue("org.mozilla.firefox").single()
        assertTrue(Browsers.isTrustedBrowser("org.mozilla.firefox", identity(next, setOf(original, next)), ""))
        assertFalse(Browsers.isTrustedBrowser("org.mozilla.firefox", identity(next, setOf(evil)), ""))
        val enrolled = Browsers.enroll("", "com.example.browser", identity(evil))
        assertTrue(Browsers.isTrustedBrowser("com.example.browser", identity(next, setOf(evil)), enrolled))
    }

    @Test fun multipleSignersRequireEveryCurrentSigner() {
        val both = Browsers.Identity(setOf(evil, next), emptySet())
        val one = Browsers.enroll("", "com.example.browser", identity(evil))
        assertFalse(Browsers.isTrustedBrowser("com.example.browser", both, one))
        assertTrue(Browsers.isTrustedBrowser("com.example.browser", both, Browsers.enroll("", "com.example.browser", both)))
    }

    @Test fun malformedPinsNeverGrantTrust() {
        listOf("com.example.browser", "com.example.browser=", "com.example.browser=xyz", "com.example.browser=$evil,xyz").forEach {
            assertEquals(emptyMap<String, Set<String>>(), Browsers.parseEnrollments(it))
            assertFalse(Browsers.isTrustedBrowser("com.example.browser", identity(evil), it))
        }
    }

    @Test fun certificateDigestKnownAnswer() {
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            Browsers.certificateDigest("abc".toByteArray()))
    }

    @Test fun officialPinsHaveValidShape() {
        val pins = Browsers.KNOWN.values.flatten()
        // Publishers can share a release key across channels. Package-scoped
        // pins, rather than global certificate uniqueness, are the boundary.
        assertTrue(pins.all { it.matches(Regex("[0-9A-F]{64}")) })
    }

    @Test fun enrollmentUpdatesPreserveSiblings() {
        val first = Browsers.enroll("", "com.example.first", identity(evil))
        val both = Browsers.enroll(first, "com.example.second", identity(next))
        assertEquals(setOf("com.example.first", "com.example.second"), Browsers.parseEnrollments(both).keys)
        assertEquals(first, Browsers.remove(both, "com.example.second"))
        assertEquals(both, Browsers.remove(both, "com.example.absent"))
        assertEquals(Browsers.parseEnrollments(first), Browsers.parseEnrollments(first + "\nmalformed\ncom.example.bad=" + evil.lowercase()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidEnrollmentCannotBeSerialized() {
        Browsers.enroll("", "invalid=package", identity(evil))
    }

    @Test fun releasePinsMatchOfflinePublisherList() {
        val snapshot = javaClass.getResourceAsStream("/browser-publishers.json")!!
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val samsungDebug = "C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8"
        val apps = snapshot.getValue("apps").jsonArray
        val recordedPackages = apps.map {
            it.jsonObject.getValue("info").jsonObject.getValue("package_name").jsonPrimitive.content
        }.toSet()
        // Mozilla pins are from the separately documented, pre-existing table.
        assertEquals(recordedPackages + setOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix"),
            Browsers.KNOWN.keys)
        apps.forEach { app ->
            val info = app.jsonObject.getValue("info").jsonObject
            val pkg = info.getValue("package_name").jsonPrimitive.content
            val release = info.getValue("signatures").jsonArray.mapNotNull { signature ->
                val value = signature.jsonObject
                val digest = value.getValue("cert_fingerprint_sha256").jsonPrimitive.content.replace(":", "")
                if (value.getValue("build").jsonPrimitive.content == "release" && digest != samsungDebug) digest else null
            }.toSet()
            val actual = Browsers.KNOWN.getValue(pkg)
            assertEquals(pkg, release, actual)
        }
    }

    @Test fun debugCertificatesAndOtherPublishersAreRejected() {
        val googleDebug = "1975B2F17177BC89A5DFF31F9E64A6CAE281A53DC1D1D59B1D147FE1C82AFA00"
        val samsungDebug = "C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8"
        Browsers.KNOWN.keys.forEach { pkg ->
            assertFalse(pkg, Browsers.isTrustedBrowser(pkg, identity(googleDebug), ""))
            assertFalse(pkg, Browsers.isTrustedBrowser(pkg, identity(samsungDebug), ""))
        }
        val chrome = Browsers.KNOWN.getValue("com.android.chrome").single()
        val samsung = Browsers.KNOWN.getValue("com.sec.android.app.sbrowser").single()
        assertFalse(Browsers.isTrustedBrowser("com.android.chrome", identity(samsung), ""))
        assertFalse(Browsers.isTrustedBrowser("com.sec.android.app.sbrowser", identity(chrome), ""))
        assertFalse(Browsers.isTrustedBrowser("com.example.browser", identity(chrome), ""))
        assertTrue(Browsers.isTrustedBrowser("com.android.chrome", identity(next, setOf(chrome)), ""))
    }
}
