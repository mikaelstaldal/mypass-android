package nu.staldal.mypass.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The service metadata is XML the framework reads, not code, so nothing else in
 * this build has an opinion about it: `aapt2` does not know the
 * `<autofill-service>` schema, and a package named there that no longer exists —
 * or one that should never have been named — fails silently at runtime.
 *
 * What is asserted is the absence of `<compatibility-package>`. Registering one
 * asks Android to derive that browser's fields from its accessibility tree.
 * Such a tree says enough for [FieldClassifier] to recognise a password box —
 * the platform synthesizes a password input type for it — but it declares no
 * origin anywhere, and the browser's URL bar, the only address the framework
 * can attach, carries no scheme while the page rather than the address bar has
 * the focus. `Matching.eligibleWebHost` then refuses, and a mode that filled
 * regardless would be releasing a password to a page MyPass cannot identify, at the
 * price of an accessibility bridge that sees every page the browser renders. If
 * that trade is ever reconsidered, it should be argued in a commit message, not
 * slipped in — which is what this test is for.
 */
class AutofillServiceMetadataTest {

    private val metadata: String = run {
        val path = "app/src/main/res/xml/autofill_service.xml"
        // Gradle may run tests from the module or from the root of the checkout.
        val file = generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) {
            it.parentFile
        }.map { File(it, path) }.firstOrNull { it.isFile }
        requireNotNull(file) { "Cannot find $path from ${System.getProperty("user.dir")}" }
        file.readText()
    }

    /** Prose about the decision is welcome; an element making it is not. */
    private val withoutComments: String = metadata.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test fun declaresTheSettingsActivityItIsFoundBy() {
        assertTrue(withoutComments.contains("android:settingsActivity=\"nu.staldal.mypass.MainActivity\""))
    }

    @Test fun registersNoBrowserForCompatibilityMode() {
        assertFalse(withoutComments.contains("compatibility-package"))
    }
}
