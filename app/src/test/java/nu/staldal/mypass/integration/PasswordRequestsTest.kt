package nu.staldal.mypass.integration

import nu.staldal.mypass.vault.PasswordEntry
import nu.staldal.mypass.vault.Secret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordRequestsTest {
    private fun entry(
        name: String,
        url: String? = null,
        realm: String? = null,
    ) = PasswordEntry(name, "$name-user", Secret("$name-password"), url, realm)

    @Test fun acceptsExactlyOneLookupKind() {
        assertEquals(PasswordQuery.Name("mail"), PasswordRequests.parse("mail", null, null))
        assertEquals(
            PasswordQuery.Site("https://example.com/login", null),
            PasswordRequests.parse(null, "https://example.com/login", null),
        )
        assertEquals(
            PasswordQuery.Site("https://example.com", "Admin"),
            PasswordRequests.parse(null, "https://example.com", "Admin"),
        )
        assertNull(PasswordRequests.parse(null, null, null))
        assertNull(PasswordRequests.parse("mail", "https://example.com", null))
        assertNull(PasswordRequests.parse("mail", null, "Admin"))
        assertNull(PasswordRequests.parse(null, null, "Admin"))
        assertNull(PasswordRequests.parse("", null, null))
        assertNull(PasswordRequests.parse("   ", null, null))
        assertNull(PasswordRequests.parse(null, "", null))
        assertNull(PasswordRequests.parse(null, "https://example.com", ""))
    }

    @Test fun nameLookupIsExact() {
        val entries = listOf(entry("mail"), entry("Mail"), entry("mailbox"))
        assertEquals(listOf("mail"), PasswordRequests.select(PasswordQuery.Name("mail"), entries).map { it.name })
    }

    @Test fun siteHostIsTheNormalizedMatchingDecision() {
        assertEquals(
            "evil.example",
            PasswordRequests.siteHost(
                PasswordQuery.Site("https://your-bank.example@Evil.Example/login", null),
            ),
        )
        assertNull(PasswordRequests.siteHost(PasswordQuery.Site("http://example.com", null)))
    }

    @Test fun resolutionControlsWhetherAChooserIsNeeded() {
        val mail = entry("mail")
        assertEquals(
            PasswordResolution.Unique(mail),
            PasswordRequests.resolve(PasswordQuery.Name("mail"), listOf(mail)),
        )
        assertEquals(
            PasswordResolution.None,
            PasswordRequests.resolve(PasswordQuery.Name("missing"), listOf(mail)),
        )
        val first = entry("first", "example.com")
        val second = entry("second", "example.com")
        assertEquals(
            PasswordResolution.Ambiguous(listOf(first, second)),
            PasswordRequests.resolve(
                PasswordQuery.Site("https://example.com", null),
                listOf(first, second),
            ),
        )
    }

    @Test fun urlLookupIsHttpsAndExactHostOnly() {
        val entries = listOf(
            entry("exact", "example.com"),
            entry("subdomain", "login.example.com"),
            entry("parent", "com"),
        )
        assertEquals(
            listOf("exact"),
            PasswordRequests.select(PasswordQuery.Site("https://example.com/path", null), entries).map { it.name },
        )
        assertEquals(
            listOf("subdomain"),
            PasswordRequests.select(PasswordQuery.Site("https://login.example.com", null), entries).map { it.name },
        )
        assertEquals(emptyList<String>(), PasswordRequests.select(PasswordQuery.Site("http://example.com", null), entries))
    }

    @Test fun realmUsesDesktopProtectionSpaceRules() {
        val entries = listOf(
            entry("fallback", "example.com"),
            entry("admin", "example.com", "Admin"),
            entry("wiki", "example.com", "Wiki"),
        )
        assertEquals(
            listOf("admin"),
            PasswordRequests.select(PasswordQuery.Site("https://example.com", "Admin"), entries).map { it.name },
        )
        assertEquals(
            listOf("fallback"),
            PasswordRequests.select(PasswordQuery.Site("https://example.com", "Other"), entries).map { it.name },
        )
        assertEquals(
            listOf("fallback"),
            PasswordRequests.select(PasswordQuery.Site("https://example.com", null), entries).map { it.name },
        )
    }
}
