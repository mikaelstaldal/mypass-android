package nu.staldal.pw.autofill

import nu.staldal.pw.data.Matching

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
    /** Identity of the enclosing HTML form, or immediate parent container. */
    val container: Any? = null,
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
 * Missing or multiple focused fields are refused. Pairing requires a shared
 * form/container and eligible origin; traversal proximity is not a boundary.
 * The origin then comes from the chosen password field, and a username field
 * from a *different* origin is dropped rather than filled: a cross-origin
 * iframe must not collect the outer page's credential.
 */
object FormSelector {

    fun <T> select(candidates: List<FieldCandidate<T>>, focusedId: T? = null): SelectedForm<T> {
        val empty = SelectedForm<T>(null, null, null, null, null, null)
        // The framework id is authoritative for browser virtual fields. If it
        // names an unclassified/missing node, refuse rather than use a flag.
        val focused = candidates.singleOrNull {
            if (focusedId != null) it.id == focusedId else it.focused
        } ?: return empty
        Matching.eligibleWebHost(focused.scheme, focused.domain) ?: return empty
        fun sameScope(field: FieldCandidate<T>): Boolean =
            focused.container != null && field.container == focused.container &&
                field.scheme == focused.scheme && field.domain == focused.domain

        val scoped = candidates.filter(::sameScope)
        val password = if (focused.kind == FieldKind.PASSWORD) focused else
            scoped.singleOrNull { it.kind == FieldKind.PASSWORD } ?: return empty
        // Do not guess among multiple username fields (e.g. registration).
        val username = if (focused.kind == FieldKind.USERNAME) focused else
            scoped.singleOrNull { it.kind == FieldKind.USERNAME }

        return SelectedForm(
            usernameId = username?.id,
            passwordId = password.id,
            webScheme = password.scheme,
            webDomain = password.domain,
            usernameValue = username?.value,
            passwordValue = password.value,
        )
    }
}
