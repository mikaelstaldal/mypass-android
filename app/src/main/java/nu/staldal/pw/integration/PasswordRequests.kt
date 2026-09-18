package nu.staldal.pw.integration

import nu.staldal.pw.data.Matching
import nu.staldal.pw.vault.PasswordEntry

/** The validated, Android-free part of an integration request. */
sealed interface PasswordQuery {
    data class Name(val name: String) : PasswordQuery
    data class Site(val origin: String, val realm: String?) : PasswordQuery
}

sealed interface PasswordResolution {
    data object None : PasswordResolution
    data class Unique(val entry: PasswordEntry) : PasswordResolution
    data class Ambiguous(val entries: List<PasswordEntry>) : PasswordResolution
}

/**
 * Validate mutually exclusive request fields and select releasable entries.
 * Keeping this free of Android types makes the security-sensitive decisions
 * ordinary JVM-testable domain logic.
 */
object PasswordRequests {
    fun parse(name: String?, url: String?, realm: String?): PasswordQuery? = when {
        name != null && name.isNotBlank() && url == null && realm == null -> PasswordQuery.Name(name)
        name == null && url != null && url.isNotBlank() && (realm == null || realm.isNotEmpty()) ->
            PasswordQuery.Site(url, realm)
        else -> null
    }

    fun select(query: PasswordQuery, entries: List<PasswordEntry>): List<PasswordEntry> = when (query) {
        is PasswordQuery.Name -> entries.filter { it.name == query.name }
        is PasswordQuery.Site -> {
            val host = Matching.originHostname(query.origin) ?: return emptyList()
            Matching.exactlyMatchingEntries(host, query.realm, entries)
        }
    }

    /** Decide whether the endpoint returns immediately or needs a chooser. */
    fun resolve(query: PasswordQuery, entries: List<PasswordEntry>): PasswordResolution {
        val matches = select(query, entries)
        return when (matches.size) {
            0 -> PasswordResolution.None
            1 -> PasswordResolution.Unique(matches.single())
            else -> PasswordResolution.Ambiguous(matches)
        }
    }

    /** The normalized host a site request will actually match, if eligible. */
    fun siteHost(query: PasswordQuery.Site): String? = Matching.originHostname(query.origin)
}
