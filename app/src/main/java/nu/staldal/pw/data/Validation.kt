package nu.staldal.pw.data

import nu.staldal.pw.vault.PasswordEntry

/**
 * Input validation, the same rules as the desktop `pw` so a vault written by
 * either side holds nothing the other would refuse.
 */
object Validation {

    /** Longest accepted entry name, username, url or realm, in code points. */
    const val MAX_NAME_LEN = 256

    /**
     * True for Unicode bidirectional control and zero-width code points that
     * can reorder or disguise how text renders without being "control"
     * characters (a Trojan-Source / homoglyph style display spoof). Used both
     * to reject such characters on input and to replace them on output.
     */
    fun isDisplaySpoofingChar(codePoint: Int): Boolean = when (codePoint) {
        // Bidirectional embeddings, overrides and isolates
        in 0x202A..0x202E, // LRE, RLE, PDF, LRO, RLO
        in 0x2066..0x2069, // LRI, RLI, FSI, PDI
        // Bidirectional marks
        0x200E, 0x200F,    // LRM, RLM
        0x061C,            // Arabic Letter Mark
        // Zero-width characters
        0x200B,            // Zero Width Space
        0x200C, 0x200D,    // ZWNJ, ZWJ
        0x2060,            // Word Joiner
        0xFEFF,            // Zero Width No-Break Space / BOM
        -> true
        else -> false
    }

    /**
     * Entry names must be non-empty, at most [MAX_NAME_LEN] code points and
     * free of control, bidirectional and zero-width characters. Everything a
     * hostname can contain is allowed.
     */
    fun validateName(name: String) {
        if (name.isEmpty()) {
            throw PwException.InvalidInput("entry name", "must not be empty")
        }
        validateText("entry name", name)
    }

    /**
     * Usernames may be empty, but obey the same length and character rules as
     * entry names.
     */
    fun validateUsername(username: String) = validateText("username", username)

    /**
     * The optional `url` matching hint, when present, must be non-empty and
     * obey the same rules. Callers map "no url" to `null`, so an empty string
     * is rejected rather than stored.
     */
    fun validateUrl(url: String) {
        if (url.isEmpty()) throw PwException.InvalidInput("url", "must not be empty")
        validateText("url", url)
    }

    /** The optional `realm` matching hint, under the same rules as `url`. */
    fun validateRealm(realm: String) {
        if (realm.isEmpty()) throw PwException.InvalidInput("realm", "must not be empty")
        validateText("realm", realm)
    }

    /**
     * Validate the pair of site-matching hints. A `realm` names one protection
     * space *on a host*, so it can only narrow a `url`; on its own it would
     * silently never match anything, which is worth refusing rather than
     * storing.
     */
    fun validateSite(url: String?, realm: String?) {
        if (url != null) validateUrl(url)
        if (realm != null) {
            validateRealm(realm)
            if (url == null) {
                throw PwException.InvalidInput(
                    "realm",
                    "needs a url: a realm names a protection space on a site",
                )
            }
        }
    }

    /** Validate the user-supplied fields of an entry before it is stored. */
    fun validateEntry(entry: PasswordEntry) {
        validateName(entry.name)
        validateUsername(entry.username)
        validateSite(entry.url, entry.realm)
    }

    /** Reject incompatible legacy metadata without echoing any producer-controlled text. */
    fun validateEntries(entries: List<PasswordEntry>) {
        val names = HashSet<String>()
        for ((index, entry) in entries.withIndex()) {
            try {
                validateEntry(entry)
            } catch (e: PwException.InvalidInput) {
                throw PwException.InvalidInput("vault entry ${index + 1} of ${entries.size} (${e.what})",
                    "${e.reason}; export the encrypted vault and correct its metadata in desktop pw before importing")
            }
            if (!names.add(entry.name)) {
                throw PwException.InvalidInput("vault", "duplicate entry name at entry ${index + 1} of ${entries.size}; export the encrypted vault and rename entries in desktop pw before importing")
            }
        }
    }

    /** Presentation only: never use for stored values, matching, or passwords. */
    fun displayText(value: String): String = buildString {
        var offset = 0
        var count = 0
        while (offset < value.length && count < MAX_NAME_LEN) {
            val cp = value.codePointAt(offset)
            if (Character.isISOControl(cp) || isDisplaySpoofingChar(cp)) append('\uFFFD')
            else appendCodePoint(cp)
            offset += Character.charCount(cp)
            count++
        }
        if (offset < value.length) append('…')
    }

    private fun validateText(what: String, value: String) {
        if (value.codePointCount(0, value.length) > MAX_NAME_LEN) {
            throw PwException.InvalidInput(what, "longer than $MAX_NAME_LEN characters")
        }
        // Character.isISOControl covers U+0000..U+001F and U+007F..U+009F,
        // which is exactly Unicode general category Cc — the same set Rust's
        // char::is_control tests.
        var i = 0
        while (i < value.length) {
            val codePoint = value.codePointAt(i)
            if (Character.isISOControl(codePoint)) {
                throw PwException.InvalidInput(what, "contains control characters")
            }
            if (isDisplaySpoofingChar(codePoint)) {
                throw PwException.InvalidInput(
                    what,
                    "contains bidirectional or zero-width characters",
                )
            }
            i += Character.charCount(codePoint)
        }
    }
}
