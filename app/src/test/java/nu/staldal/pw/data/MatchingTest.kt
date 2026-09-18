package nu.staldal.pw.data

import nu.staldal.pw.vault.PasswordEntry
import nu.staldal.pw.vault.Secret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop `pw`'s browser-integration matching tests, ported one for one.
 * A divergence here is a divergence in which sites may receive which
 * credential, so they are worth keeping in step.
 */
class MatchingTest {

    private fun withUrl(name: String, url: String) = PasswordEntry(
        name = name,
        username = "user",
        password = Secret("pw"),
        url = url,
    )

    private fun withRealm(name: String, url: String, realm: String?) =
        withUrl(name, url).copy(realm = realm)

    /**
     * Run [Matching.matchingEntries] for [hostname] over entries whose `url` is
     * each of [urls]. The entry name is deliberately unrelated to the host
     * (matching is on `url` only), and the result is the matched `url`s.
     */
    private fun matches(hostname: String, vararg urls: String): List<String> {
        val entries = urls.mapIndexed { i, u -> withUrl("entry-$i", u) }
        return Matching.matchingEntries(hostname, entries).mapNotNull { it.url }
    }

    @Test
    fun matchesExactHostname() {
        assertEquals(listOf("github.com"), matches("github.com", "github.com"))
    }

    @Test
    fun matchesParentDomainAtLabelBoundary() {
        assertEquals(
            listOf("example.co.uk"),
            matches("login.example.co.uk", "example.co.uk"),
        )
        // A subdomain entry never matches a shorter requested host.
        assertTrue(matches("example.co.uk", "login.example.co.uk").isEmpty())
    }

    @Test
    fun doesNotMatchAcrossPublicSuffix() {
        // The registrable-domain cut-off stops `co.uk` / `com` matching, so a
        // shared suffix cannot leak credentials between sites.
        assertTrue(matches("login.example.co.uk", "co.uk").isEmpty())
        assertTrue(matches("github.com", "com").isEmpty())
    }

    @Test
    fun doesNotMatchNonBoundaryOrSibling() {
        // Not a label boundary: `hub.com` is a suffix of `github.com` only as
        // a substring, never as a parent domain.
        assertTrue(matches("github.com", "hub.com").isEmpty())
        // A phishing host cannot borrow the victim's entry...
        assertTrue(matches("github.com.evil.example", "github.com").isEmpty())
        // ...and only `evil.example`'s own entry matches it.
        assertEquals(
            listOf("evil.example"),
            matches("github.com.evil.example", "evil.example"),
        )
    }

    @Test
    fun matchesCaseInsensitively() {
        assertEquals(listOf("GitHub.COM"), matches("github.com", "GitHub.COM"))
    }

    @Test
    fun matchesAfterIdnaNormalization() {
        // The request arrives as punycode (as a browser reports it); the entry
        // is named in Unicode. Both normalize to the same ASCII form.
        assertEquals(
            listOf("bücher.example"),
            matches("xn--bcher-kva.example", "bücher.example"),
        )
    }

    /**
     * The deviation characters, where IDNA2003 (`java.net.IDN`) and UTS #46
     * non-transitional (Rust's `idna`, browsers, and now this app) disagree.
     * The expected values were taken from `idna` 1.1.0 itself, the exact
     * version desktop pw depends on, so this test is what keeps the two sides
     * naming the same real host.
     */
    @Test
    fun idnaUsesNonTransitionalUts46LikeTheDesktop() {
        // Eszett is kept, not folded to "ss" (which would be fass.de).
        assertEquals("xn--fa-hia.de", Matching.normalizeHost("faß.de"))
        // Final sigma is kept, not folded to a medial sigma. This is the case
        // that matters in practice: plenty of Greek words end in one.
        assertEquals("xn--hxan1bmi.example", Matching.normalizeHost("γάτος.example"))
        assertEquals(
            "xn--kxa6ajbbmh.example",
            Matching.normalizeHost("σίσυφος.example"),
        )
        // Uppercase sigma lowercases to a medial sigma under both standards.
        assertEquals(
            "xn--kxa6akbbkh.example",
            Matching.normalizeHost("ΣΊΣΥΦΟΣ.example"),
        )
        // The undisputed cases still work.
        assertEquals("xn--bcher-kva.example", Matching.normalizeHost("bücher.example"))
        assertEquals("github.com", Matching.normalizeHost("GitHub.COM"))
        assertEquals("xn--bcher-kva.example", Matching.normalizeHost("xn--bcher-kva.example"))
    }

    @Test
    fun deviationCharactersMatchTheHostTheDesktopWouldMatch() {
        // End to end: an entry written on the desktop for fa<eszett>.de is
        // released to the host the desktop would release it to, and not to the
        // IDNA2003 folding of it.
        assertEquals(listOf("faß.de"), matches("xn--fa-hia.de", "faß.de"))
        assertTrue(matches("fass.de", "faß.de").isEmpty())
    }

    @Test
    fun unusableHostnamesNormalizeToNothing() {
        for (host in listOf("", ".", ".leading.example", "trailing.example.", "a..b")) {
            assertNull(host, Matching.normalizeHost(host))
        }
        // Hyphens anywhere are accepted, as Rust's domain_to_ascii accepts
        // them: ICU's CheckHyphens errors are deliberately ignored.
        assertEquals("-lead.example", Matching.normalizeHost("-lead.example"))
        assertEquals("trail-.example", Matching.normalizeHost("trail-.example"))
        assertEquals("ab--cd.example", Matching.normalizeHost("ab--cd.example"))
        // ...but a malformed punycode label is still refused.
        assertNull(Matching.normalizeHost("xn--a.example"))
    }

    @Test
    fun localhostMatchesOnlyExactly() {
        assertEquals(listOf("localhost"), matches("localhost", "localhost"))
        assertTrue(matches("localhost", "host").isEmpty())
        assertEquals(listOf("127.0.0.1"), matches("127.0.0.1", "127.0.0.1"))
    }

    @Test
    fun returnsAllCandidates() {
        assertEquals(
            listOf("github.com", "login.github.com"),
            matches("login.github.com", "github.com", "login.github.com", "other.com"),
        )
    }

    @Test
    fun urlFieldMatchesWhenNameDoesNot() {
        val entries = listOf(withUrl("work-github", "github.com"))
        assertEquals(
            listOf("work-github"),
            Matching.matchingEntries("github.com", entries).map { it.name },
        )
    }

    @Test
    fun entryWithoutUrlNeverMatches() {
        // An entry whose name is the hostname but which has no url is not
        // eligible for browser use.
        val entries = listOf(
            PasswordEntry(name = "github.com", username = "user", password = Secret("pw"))
        )
        assertTrue(Matching.matchingEntries("github.com", entries).isEmpty())
    }

    @Test
    fun nameIsNotMatchedAgainstHost() {
        // The name equals the host, but the url points elsewhere: no match.
        val entries = listOf(withUrl("github.com", "example.com"))
        assertTrue(Matching.matchingEntries("github.com", entries).isEmpty())
    }

    @Test
    fun urlFieldAcceptsFullUrl() {
        // Only the host is used, and the parent-domain rule still applies.
        val entries = listOf(withUrl("work", "https://github.com/login?next=/"))
        assertEquals(1, Matching.matchingEntries("login.github.com", entries).size)
    }

    @Test
    fun urlFieldAcceptsPortAndUserinfo() {
        assertEquals(
            listOf("https://user@github.com:8443/login"),
            matches("github.com", "https://user@github.com:8443/login"),
        )
    }

    @Test
    fun urlFieldRespectsPublicSuffixBoundary() {
        assertTrue(
            Matching.matchingEntries("login.example.co.uk", listOf(withUrl("x", "co.uk")))
                .isEmpty()
        )
    }

    @Test
    fun urlFieldDoesNotMatchUnrelatedHost() {
        assertTrue(matches("example.com", "github.com").isEmpty())
    }

    @Test
    fun emptyOrMalformedValuesNeverMatch() {
        assertTrue(matches("example.com", "").isEmpty())
        assertTrue(Matching.matchingEntries("", listOf(withUrl("x", "example.com"))).isEmpty())
        assertTrue(matches("example.com", "example..com").isEmpty())
    }

    @Test
    fun entriesOnHostRejectsParentAndChildDomains() {
        // The heart of it: "is this login already in the vault?" is a
        // different question from "what may fill here?".
        val entries = listOf(
            withUrl("parent", "example.com"),
            withUrl("exact", "login.example.com"),
            withUrl("child", "deep.login.example.com"),
            withUrl("other", "example.org"),
        )
        assertEquals(
            listOf("exact"),
            Matching.entriesOnHost("login.example.com", entries).map { it.name },
        )
        // ...while the ordinary, user-driven fill still accepts the parent.
        assertEquals(
            listOf("parent", "exact"),
            Matching.matchingEntries("login.example.com", entries).map { it.name },
        )
    }

    @Test
    fun entriesOnHostNormalizesLikeEverythingElse() {
        assertEquals(
            listOf("a"),
            Matching.entriesOnHost("github.com", listOf(withUrl("a", "GitHub.com"))).map { it.name },
        )
        assertEquals(
            listOf("a"),
            Matching.entriesOnHost(
                "github.com",
                listOf(withUrl("a", "https://user@github.com:8443/login")),
            ).map { it.name },
        )
        assertEquals(
            1,
            Matching.entriesOnHost(
                "xn--bcher-kva.example",
                listOf(withUrl("a", "bücher.example")),
            ).size,
        )
        assertTrue(Matching.entriesOnHost("", listOf(withUrl("a", "example.com"))).isEmpty())
    }

    @Test
    fun realmSelectsTheEntryNamingIt() {
        val entries = listOf(
            withRealm("admin", "example.com", "Admin"),
            withRealm("wiki", "example.com", "Wiki"),
        )
        assertEquals(
            listOf("admin"),
            Matching.exactlyMatchingEntries("example.com", "Admin", entries).map { it.name },
        )
    }

    @Test
    fun aRealmedEntryIsNeverReleasedToAnotherRealm() {
        val entries = listOf(withRealm("admin", "example.com", "Admin"))
        assertTrue(Matching.exactlyMatchingEntries("example.com", "Wiki", entries).isEmpty())
        assertTrue(Matching.exactlyMatchingEntries("example.com", null, entries).isEmpty())
    }

    @Test
    fun anUnrealmedEntryIsAWildcardOverItsHost() {
        val entries = listOf(withRealm("site", "example.com", null))
        for (realm in listOf("Admin", "Wiki", null)) {
            assertEquals(
                listOf("site"),
                Matching.exactlyMatchingEntries("example.com", realm, entries).map { it.name },
            )
        }
    }

    @Test
    fun aNamedRealmWinsOverTheWildcard() {
        val entries = listOf(
            withRealm("catch-all", "example.com", null),
            withRealm("admin", "example.com", "Admin"),
        )
        assertEquals(
            listOf("admin"),
            Matching.exactlyMatchingEntries("example.com", "Admin", entries).map { it.name },
        )
        assertEquals(
            listOf("catch-all"),
            Matching.exactlyMatchingEntries("example.com", "Other", entries).map { it.name },
        )
    }

    @Test
    fun realmsAreComparedAsExactStrings() {
        val entries = listOf(withRealm("admin", "example.com", "Admin"))
        assertTrue(Matching.exactlyMatchingEntries("example.com", "admin", entries).isEmpty())
        assertTrue(Matching.exactlyMatchingEntries("example.com", "Admin ", entries).isEmpty())
    }

    @Test
    fun realmDoesNotLoosenHostMatching() {
        val entries = listOf(withRealm("admin", "example.com", "Admin"))
        assertTrue(Matching.exactlyMatchingEntries("evil.example.com", "Admin", entries).isEmpty())
    }

    @Test
    fun unknownTopLevelDomainsStillAllowParentMatching() {
        // The Public Suffix List's own default rule is that an unrecognised
        // suffix is one label, so `evil.example` is registrable and can match
        // its own subdomains. Guava stops at the list, so pw fills this in;
        // without it `.example`, `.internal`, `.home` and every other unlisted
        // TLD would behave differently here than in desktop pw.
        assertEquals(
            listOf("evil.example"),
            matches("github.com.evil.example", "evil.example"),
        )
        assertEquals(listOf("corp.internal"), matches("host.corp.internal", "corp.internal"))
        // The implied suffix still bounds it: a bare TLD matches nothing.
        assertTrue(matches("host.corp.internal", "internal").isEmpty())
    }

    @Test
    fun originHostnameAcceptsHttps() {
        assertEquals("github.com", Matching.originHostname("https://github.com"))
        assertEquals(
            "login.example.co.uk",
            Matching.originHostname("https://login.example.co.uk:8443"),
        )
        assertEquals("github.com", Matching.originHostname("https://GitHub.com"))
    }

    @Test
    fun originHostnameRejectsNonHttps() {
        for (origin in listOf(
            "http://github.com",
            "file:///etc/passwd",
            "moz-extension://abc/page.html",
            "ftp://github.com",
            "github.com",
            "",
        )) {
            assertNull(origin, Matching.originHostname(origin))
        }
    }

    @Test
    fun originHostnameAllowsLocalHttpForDev() {
        assertEquals("localhost", Matching.originHostname("http://localhost:3000"))
        assertEquals("127.0.0.1", Matching.originHostname("http://127.0.0.1:8080"))
    }

    @Test
    fun eligibleWebHostAppliesTheSameRule() {
        assertEquals("github.com", Matching.eligibleWebHost("https", "github.com"))
        assertEquals("github.com", Matching.eligibleWebHost("HTTPS", "GitHub.com"))
        assertEquals("localhost", Matching.eligibleWebHost("http", "localhost"))
        assertNull(Matching.eligibleWebHost("http", "github.com"))
        // A browser that reports no scheme is not assumed to mean https.
        assertNull(Matching.eligibleWebHost(null, "github.com"))
        assertNull(Matching.eligibleWebHost("https", null))
        assertNull(Matching.eligibleWebHost("https", ""))
    }
}
