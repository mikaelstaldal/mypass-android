package nu.staldal.pw.autofill

/**
 * One classified text field, in the order it was found in the view tree.
 *
 * Generic in the id type so the selection rules below can be exercised as
 * plain JVM tests; the service instantiates it with
 * [android.view.autofill.AutofillId].
 */
data class FieldCandidate<T>(
    val kind: FieldKind,
    val id: T,
    /** Scheme of the page this field is on, inherited from its ancestors. */
    val scheme: String?,
    /** Host of the page this field is on, inherited from its ancestors. */
    val domain: String?,
    /** What the field holds right now, for a save request. */
    val value: String?,
    /** Whether this is the field the user tapped, which raised the request. */
    val focused: Boolean = false,
)

/** The pair of fields [FormSelector] picked, plus the origin they belong to. */
data class SelectedForm<T>(
    val usernameId: T?,
    val passwordId: T?,
    val webScheme: String?,
    val webDomain: String?,
    val usernameValue: String?,
    val passwordValue: String?,
)

/**
 * Picks the one login form to answer for, out of everything the page put in
 * front of us.
 *
 * A page is not one form. A sign-in page often carries a registration form
 * too, and a page with frames carries whatever they contain. Taking the first
 * password field in the whole tree would answer for whichever form happens to
 * come first in the document, which may not be the one the user is standing
 * in — so the field the user actually focused, which is what raised the
 * request, decides.
 *
 * The origin then comes from the chosen password field, and a username field
 * from a *different* origin is dropped rather than filled: a cross-origin
 * iframe must not collect the outer page's credential.
 */
object FormSelector {

    fun <T> select(candidates: List<FieldCandidate<T>>): SelectedForm<T> {
        val focusedIndex = candidates.indexOfFirst { it.focused }
        val focused = candidates.getOrNull(focusedIndex)

        val passwordIndex = when {
            // The user is in the password box: that is the form, full stop.
            focused?.kind == FieldKind.PASSWORD -> focusedIndex
            // The user is in a username box: its form's password box is the
            // next one below it. Fall back upwards for the layouts that put
            // the password first, and only then give up and take any.
            focused?.kind == FieldKind.USERNAME ->
                candidates.indexOfFirstFrom(focusedIndex + 1) { it.kind == FieldKind.PASSWORD }
                    .orElse {
                        candidates.indexOfLastBefore(focusedIndex) { it.kind == FieldKind.PASSWORD }
                    }
                    .orElse { candidates.indexOfFirst { it.kind == FieldKind.PASSWORD } }

            else -> candidates.indexOfFirst { it.kind == FieldKind.PASSWORD }
        }
        val password = candidates.getOrNull(passwordIndex)

        val username = when {
            focused?.kind == FieldKind.USERNAME -> focused
            // The username field of a login form sits above its password
            // field. Prefer the nearest one before it; fall back to the first
            // after, for the layouts that put them in the other order.
            passwordIndex >= 0 ->
                candidates.getOrNull(
                    candidates.indexOfLastBefore(passwordIndex) { it.kind == FieldKind.USERNAME }
                        .orElse {
                            candidates.indexOfFirstFrom(passwordIndex + 1) {
                                it.kind == FieldKind.USERNAME
                            }
                        }
                )

            else -> candidates.firstOrNull { it.kind == FieldKind.USERNAME }
        }

        // Same-origin only: a username field in another frame is not part of
        // this form as far as pw is concerned.
        val sameOrigin = password == null || username == null ||
            (username.scheme == password.scheme && username.domain == password.domain)

        return SelectedForm(
            usernameId = username?.id?.takeIf { sameOrigin },
            passwordId = password?.id,
            webScheme = password?.scheme ?: username?.scheme,
            webDomain = password?.domain ?: username?.domain,
            usernameValue = username?.value?.takeIf { sameOrigin },
            passwordValue = password?.value,
        )
    }

    private fun <T> List<FieldCandidate<T>>.indexOfFirstFrom(
        from: Int,
        predicate: (FieldCandidate<T>) -> Boolean,
    ): Int {
        for (i in from.coerceAtLeast(0) until size) if (predicate(this[i])) return i
        return -1
    }

    private fun <T> List<FieldCandidate<T>>.indexOfLastBefore(
        before: Int,
        predicate: (FieldCandidate<T>) -> Boolean,
    ): Int {
        for (i in (before - 1).coerceAtMost(size - 1) downTo 0) if (predicate(this[i])) return i
        return -1
    }

    /** Chain index lookups, `-1` meaning "not found". */
    private inline fun Int.orElse(next: () -> Int): Int = if (this >= 0) this else next()
}
