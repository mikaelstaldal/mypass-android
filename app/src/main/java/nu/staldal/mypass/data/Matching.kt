package nu.staldal.mypass.data

import com.google.common.net.InternetDomainName
import com.ibm.icu.text.IDNA
import java.util.Locale
import nu.staldal.mypass.vault.PasswordEntry

/**
 * Which entries a visited web page may be filled from.
 *
 * These are the desktop `MyPass`'s browser-integration matching rules, unchanged:
 * an entry matches a page when the host part of its `url` equals the page's
 * hostname or is a parent domain of it at a label boundary, climbing no
 * further than the registrable domain (eTLD+1, via the Public Suffix List).
 * Matching is case-insensitive and IDNA/punycode-normalized, and an entry
 * without a `url` is never eligible — the entry `name` is never matched
 * against the visited host.
 */
object Matching {

    /**
     * Extract the hostname from a web origin, applying the eligibility rules
     * of the browser integration: only `https:` origins are accepted, plus
     * `http://localhost` and `http://127.0.0.1` for local development. The
     * returned hostname is IDNA/punycode-normalized and lowercased. Any
     * ineligible or unparsable origin yields `null`; no other scheme is
     * accepted.
     */
    fun originHostname(origin: String): String? {
        val separator = origin.indexOf("://")
        if (separator < 0) return null
        val scheme = origin.substring(0, separator)
        val host = normalizeHost(hostFromAuthority(origin.substring(separator + 3))) ?: return null
        return eligibleHost(scheme, host)
    }

    /**
     * The same eligibility rule for a scheme and host that arrive already
     * separated, as they do from Android's autofill framework
     * (`ViewNode.getWebScheme()` / `getWebDomain()`).
     *
     * A missing scheme is *not* treated as `https:`: the whole point of the
     * rule is that a page served over plain HTTP never receives a password,
     * and "we could not tell" is not "it was fine".
     */
    fun eligibleWebHost(scheme: String?, domain: String?): String? {
        if (scheme == null || domain == null) return null
        val host = normalizeHost(hostFromAuthority(domain)) ?: return null
        return eligibleHost(scheme, host)
    }

    private fun eligibleHost(scheme: String, normalizedHost: String): String? =
        when (scheme.lowercase(Locale.ROOT)) {
            "https" -> normalizedHost
            "http" -> normalizedHost.takeIf { it == "localhost" || it == "127.0.0.1" }
            else -> null
        }

    /**
     * Reduce the part of a URL after `scheme://` to its bare host: drop any
     * path/query/fragment, then any userinfo, then the port. A bracketed IPv6
     * literal is never an eligible host in this integration, so a plain
     * rightmost-colon split for the port is sufficient.
     */
    private fun hostFromAuthority(afterScheme: String): String {
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val afterUserinfo = authority.substringAfterLast('@')
        val colon = afterUserinfo.lastIndexOf(':')
        return if (colon >= 0) afterUserinfo.substring(0, colon) else afterUserinfo
    }

    /**
     * Entries that match [hostname]. An entry matches when the host part of
     * its `url` equals the hostname (exact match) or is a parent domain of it
     * at a label boundary, climbing no further than the registrable domain
     * (eTLD+1, via the Public Suffix List). All values are IDNA/punycode-
     * normalized and compared case-insensitively, so `example.co.uk` matches
     * `login.example.co.uk` but `co.uk` matches nothing.
     */
    fun matchingEntries(hostname: String, entries: List<PasswordEntry>): List<PasswordEntry> {
        val host = normalizeHost(hostname) ?: return emptyList()
        // The registrable domain bounds how far a parent-domain match may
        // climb. A host with no registrable domain — a bare IP, `localhost`,
        // or a public suffix itself — admits only an exact match.
        val minLabels = labelCount(registrableDomain(host) ?: host)
        return entries.filter { entry ->
            val candidate = entry.url?.let { urlHost(it) }
            candidate != null && hostMatches(host, candidate, minLabels)
        }
    }

    /**
     * Entries whose `url` host is *exactly* [hostname], with no parent-domain
     * match.
     *
     * [matchingEntries] deliberately accepts a parent domain, which is right
     * for a fill the user asks for on the page in front of them. It is wrong
     * when the question is "is this login already in the vault?", as it is when
     * the browser offers to save one: an entry for `example.com` is not the
     * entry for `login.example.com`, and updating it would silently rotate the
     * wrong password.
     *
     * This is the desktop `MyPass`'s `exactly_matching_entries` without its realm
     * narrowing, which has no meaning outside HTTP authentication.
     */
    fun entriesOnHost(hostname: String, entries: List<PasswordEntry>): List<PasswordEntry> {
        val host = normalizeHost(hostname) ?: return emptyList()
        return entries.filter { entry -> entry.url?.let { urlHost(it) } == host }
    }

    /**
     * Entries releasable to one HTTP-authentication protection space. Host
     * matching is exact: a credential for a parent domain must never be
     * released silently to a subdomain. An entry naming [realm] wins over an
     * unscoped entry; otherwise an unscoped entry is the backwards-compatible
     * wildcard. A request without a realm matches only unscoped entries.
     *
     * This is desktop MyPass's `exactly_matching_entries`, kept here as a domain
     * rule so Android integration components do not invent matching policy.
     */
    fun exactlyMatchingEntries(
        hostname: String,
        realm: String?,
        entries: List<PasswordEntry>,
    ): List<PasswordEntry> {
        val onHost = entriesOnHost(hostname, entries)
        val named = realm?.let { requested ->
            onHost.filter { it.realm == requested }
        }.orEmpty()
        return named.ifEmpty { onHost.filter { it.realm == null } }
    }

    /**
     * UTS #46 (IDNA2008) non-transitional processing, the same profile Rust's
     * `idna` crate — and therefore desktop `MyPass` — applies, and the same one
     * browsers apply when they resolve a name.
     *
     * **Not `java.net.IDN`.** That implements the older IDNA2003, which maps
     * the four *deviation characters* instead of leaving them alone, so the
     * two sides would disagree about which real host an entry names:
     *
     * | host             | `java.net.IDN`              | UTS #46 non-transitional |
     * |------------------|-----------------------------|--------------------------|
     * | `fa` + ß + `.de` | `fass.de`                   | `xn--fa-hia.de`          |
     * | `gato` + ς       | `xn--hxan1bqd.example`      | `xn--hxan1bmi.example`   |
     *
     * The second line is not an exotic case: final sigma ends a great many
     * ordinary Greek words. An entry shared with the desktop would have been
     * released to one host here and a different host there — a disagreement
     * about which site gets a credential, which is the one thing this project
     * is not allowed to get wrong.
     */
    private val UTS46: IDNA = IDNA.getUTS46Instance(
        IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ
    )

    /**
     * The errors ICU reports that Rust's `domain_to_ascii` does not, because
     * it runs with `Hyphens::Allow`. Ignoring them keeps the two sides
     * agreeing; every other error class stays fatal, so an unusable name
     * yields `null` and matches nothing.
     */
    private val IGNORED_IDNA_ERRORS = setOf(
        IDNA.Error.LEADING_HYPHEN,
        IDNA.Error.TRAILING_HYPHEN,
        IDNA.Error.HYPHEN_3_4,
    )

    /**
     * IDNA/punycode-normalize a hostname to lowercase ASCII, or `null` if it
     * is not a usable domain.
     */
    internal fun normalizeHost(host: String): String? {
        if (host.isEmpty()) return null
        // UTS #46 tolerates a trailing dot and empty labels that a hostname
        // must not have; reject them before it can normalize them away. This
        // is stricter than the desktop, which accepts them and then matches
        // nothing — the difference is fail-closed either way.
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) return null
        val ascii = StringBuilder()
        val info = IDNA.Info()
        UTS46.nameToASCII(host, ascii, info)
        if (info.errors.any { it !in IGNORED_IDNA_ERRORS }) return null
        // UTS #46 already lowercases what it maps; an all-ASCII name is
        // returned untouched, so lowercase it here.
        return ascii.toString().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    /**
     * Extract and normalize the host from an entry's `url`, which may be a
     * bare hostname (`github.com`), a host:port, or a full URL
     * (`https://github.com/login`). Unlike [originHostname] this places no
     * constraint on the scheme — it is a stored matching hint, not an incoming
     * request — so any scheme (or none) is accepted and only the host is kept.
     */
    internal fun urlHost(url: String): String? {
        val separator = url.indexOf("://")
        val afterScheme = if (separator < 0) url else url.substring(separator + 3)
        return normalizeHost(hostFromAuthority(afterScheme))
    }

    /**
     * The registrable domain (eTLD+1) of an already-normalized host, or `null`
     * when it has none: a single-label name such as `localhost`, or a public
     * suffix itself such as `co.uk`.
     *
     * Guava supplies the Public Suffix List but stops at the list: a host under
     * a TLD the list does not know (`evil.example`, or an IP address) reports
     * no public suffix at all. The list's own default rule is that an
     * unrecognised suffix is `*` — one label — so that case is filled in here.
     * Without it a name under an unknown TLD would admit only an exact match,
     * where Rust's `psl` crate, and therefore desktop `MyPass`, allows the ordinary
     * parent-domain match.
     */
    private fun registrableDomain(host: String): String? {
        val name = try {
            InternetDomainName.from(host)
        } catch (e: IllegalArgumentException) {
            return impliedRegistrableDomain(host)
        }
        return when {
            name.isUnderPublicSuffix -> name.topPrivateDomain().toString()
            // The host *is* a public suffix (`co.uk`, `com`): nothing is
            // registrable under it here, so only an exact match is allowed.
            name.hasPublicSuffix() -> null
            else -> impliedRegistrableDomain(host)
        }
    }

    /** The last two labels, under the Public Suffix List's implicit `*` rule. */
    private fun impliedRegistrableDomain(host: String): String? {
        val labels = host.split('.').filter { it.isNotEmpty() }
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else null
    }

    private fun labelCount(domain: String): Int = domain.split('.').count { it.isNotEmpty() }

    /**
     * Whether [name] (already normalized) matches [host] (already normalized):
     * an exact match, or a parent-domain match landing on a label boundary and
     * having at least [minLabels] labels so it never climbs past the
     * registrable domain.
     */
    private fun hostMatches(host: String, name: String, minLabels: Int): Boolean {
        if (name == host) return true
        return labelCount(name) >= minLabels &&
            host.length > name.length &&
            host.endsWith(name) &&
            host[host.length - name.length - 1] == '.'
    }
}
