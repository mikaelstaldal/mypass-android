package nu.staldal.pw.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsersTest {

    @Test
    fun knownBrowsersAreTrusted() {
        assertTrue(Browsers.isTrustedBrowser("org.mozilla.firefox", ""))
        assertTrue(Browsers.isTrustedBrowser("com.android.chrome", ""))
    }

    @Test
    fun anythingElseIsNot() {
        // The whole point: an ordinary app may claim any web domain it likes,
        // so its claim is not believed.
        assertFalse(Browsers.isTrustedBrowser("com.example.evil", ""))
        assertFalse(Browsers.isTrustedBrowser("", ""))
        assertFalse(Browsers.isTrustedBrowser(null, ""))
    }

    @Test
    fun extraPackagesFromSettingsAreTrusted() {
        assertTrue(Browsers.isTrustedBrowser("com.example.browser", "com.example.browser"))
        assertTrue(
            Browsers.isTrustedBrowser("com.example.browser", "a.b, com.example.browser , c.d")
        )
        assertFalse(Browsers.isTrustedBrowser("com.example.other", "com.example.browser"))
    }

    @Test
    fun extraPackagesSplitOnCommasAndWhitespace() {
        assertEquals(
            setOf("a.b", "c.d", "e.f"),
            Browsers.parseExtraPackages("a.b, c.d\n e.f  ,"),
        )
        assertEquals(emptySet<String>(), Browsers.parseExtraPackages("  , ,"))
    }
}
