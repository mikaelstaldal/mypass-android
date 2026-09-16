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
    /** Why there is no form here, and what that was decided from. */
    val diagnosis: FormDiagnosis = FormDiagnosis(),
)

/** Which of [FormSelector]'s rules declined to name a form. */
enum class FormRefusal {
    NO_CLASSIFIED_FIELD,
    NO_FOCUSED_FIELD,
    INELIGIBLE_ORIGIN,
    NO_SHARED_CONTAINER,
    NO_PASSWORD_FIELD,
    AMBIGUOUS_PASSWORD,
}

/**
 * What [FormSelector] saw, for [FillDiagnostics] alone. Nothing here may feed
 * a fill decision: [focusedScheme] and [focusedHost] are what the browser
 * *claimed*, recorded before the eligibility rules judged them, which is
 * precisely why they are worth showing and why they must not be acted on.
 */
data class FormDiagnosis(
    val refusal: FormRefusal? = null,
    val classifiedFields: Int = 0,
    val focusedScheme: String? = null,
    val focusedHost: String? = null,
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
 *
 * When nothing on the page identifies a username field, the username is the
 * nearest text input *preceding* the password inside the same form. That is
 * desktop pw's rule verbatim (`webextension/fill.js` `findUsernameField`), and
 * it is what makes a form work whose username box declares nothing at all —
 * `<input type="text">` with no name, no id and `autocomplete="off"` is
 * common, and no amount of name-matching will ever recognise it.
 */
object FormSelector {

    fun <T> select(candidates: List<FieldCandidate<T>>, focusedId: T? = null): SelectedForm<T> {
        val seen = candidates.size
        fun refuse(refusal: FormRefusal, focused: FieldCandidate<T>? = null) = SelectedForm<T>(
            null, null, null, null, null, null,
            FormDiagnosis(refusal, seen, focused?.scheme, focused?.domain),
        )

        // The framework id is authoritative for browser virtual fields. If it
        // names an unclassified/missing node, refuse rather than use a flag.
        val focused = candidates.singleOrNull {
            if (focusedId != null) it.id == focusedId else it.focused
        } ?: return refuse(
            if (candidates.isEmpty()) FormRefusal.NO_CLASSIFIED_FIELD else FormRefusal.NO_FOCUSED_FIELD
        )
        Matching.eligibleWebHost(focused.scheme, focused.domain)
            ?: return refuse(FormRefusal.INELIGIBLE_ORIGIN, focused)
        fun sameScope(field: FieldCandidate<T>): Boolean =
            focused.container != null && field.container == focused.container &&
                field.scheme == focused.scheme && field.domain == focused.domain

        val scoped = candidates.filter(::sameScope)
        val passwords = scoped.filter { it.kind == FieldKind.PASSWORD }
        val password = if (focused.kind == FieldKind.PASSWORD) focused else
            passwords.singleOrNull() ?: return refuse(
                when {
                    // A focused field with no container admits only itself, and
                    // it is not the password, so there is nothing to pair with.
                    focused.container == null -> FormRefusal.NO_SHARED_CONTAINER
                    passwords.isEmpty() -> FormRefusal.NO_PASSWORD_FIELD
                    else -> FormRefusal.AMBIGUOUS_PASSWORD
                },
                focused,
            )
        val named = scoped.filter { it.kind == FieldKind.USERNAME }
        val username = when {
            // A field the user tapped needs no inference: they said where the
            // username goes, and a password is never it.
            focused.kind != FieldKind.PASSWORD -> focused
            named.size == 1 -> named.single()
            // Do not guess among multiple username fields (e.g. registration).
            named.isNotEmpty() -> null
            else -> nearestPrecedingText(scoped, password)
        }

        return SelectedForm(
            usernameId = username?.id,
            passwordId = password.id,
            webScheme = password.scheme,
            webDomain = password.domain,
            usernameValue = username?.value,
            passwordValue = password.value,
            diagnosis = FormDiagnosis(null, seen, focused.scheme, focused.domain),
        )
    }

    /**
     * The last text input before [password] in the same scope — "nearest
     * preceding", in the tree order the candidates were collected in, which is
     * document order for a browser's structure.
     *
     * Only ever reached when the page identified no username field at all, so
     * the alternative it is being weighed against is filling nothing.
     */
    private fun <T> nearestPrecedingText(
        scoped: List<FieldCandidate<T>>,
        password: FieldCandidate<T>,
    ): FieldCandidate<T>? = scoped.takeWhile { it !== password }
        .lastOrNull { it.kind == FieldKind.TEXT }
}
