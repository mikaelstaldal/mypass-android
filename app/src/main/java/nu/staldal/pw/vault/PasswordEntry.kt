package nu.staldal.pw.vault

import kotlinx.serialization.Serializable

/**
 * One vault entry. The JSON shape is the desktop `pw`'s, byte for byte:
 * `name`, `username` and `password` are always written, `url` and `realm`
 * only when set, so a vault round-trips between the two without growing
 * fields.
 */
@Serializable
data class PasswordEntry(
    val name: String,
    val username: String,
    val password: Secret,
    /**
     * Optional site the entry is for. It is the sole association between an
     * entry and a website: the autofill service releases this entry to a
     * visited page when the page's host matches the `url`'s host. An entry
     * must have a `url` set to be usable in a web browser; the entry [name] is
     * never matched against the visited host. May be a bare hostname
     * (`github.com`) or a full URL (`https://github.com/login`); only the host
     * part is used for matching.
     */
    val url: String? = null,
    /**
     * Optional HTTP-authentication realm the entry is for, narrowing [url] to
     * a single protection space.
     *
     * Android's autofill framework never sees an HTTP authentication
     * challenge — the browser answers those with its own dialog — so nothing
     * in this app matches on it. It is kept, edited and round-tripped so that
     * a vault shared with desktop `pw`, whose Firefox integration does use it,
     * survives a write from this app unchanged.
     */
    val realm: String? = null,
)
