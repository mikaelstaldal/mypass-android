package nu.staldal.pw.autofill

import android.text.InputType
import android.view.View
import java.util.Locale

/** What a field in a login form is, as far as pw cares. */
enum class FieldKind { USERNAME, PASSWORD }

/**
 * Everything one view says about itself that bears on what kind of field it is,
 * lifted out of [android.app.assist.AssistStructure] so the rules below can be
 * tested without an emulator.
 */
data class FieldSignals(
    /** `View.getAutofillHints()`, the page's own declaration. */
    val autofillHints: List<String> = emptyList(),
    /** The HTML tag the browser passed through, if this came from a web page. */
    val htmlTag: String? = null,
    /** That tag's attributes, lowercased by name. */
    val htmlAttributes: Map<String, String> = emptyMap(),
    /** `View.getInputType()`, what Android inferred. */
    val inputType: Int = 0,
)

/**
 * Decides what a field is, in decreasing order of how much the page had to say
 * about it: the autofill hints it declares, then the HTML attributes the
 * browser passed through, then the input type Android inferred.
 *
 * Getting this wrong is a usability failure, never a security one — the entry
 * released is decided by the site, not by which box the text lands in — so the
 * heuristics lean towards recognising a login form rather than away from it.
 */
object FieldClassifier {

    fun classify(signals: FieldSignals): FieldKind? =
        fromHints(signals) ?: fromHtml(signals) ?: fromInputType(signals)

    private fun fromHints(signals: FieldSignals): FieldKind? {
        for (hint in signals.autofillHints) {
            val normalized = hint.lowercase(Locale.ROOT)
            if (normalized in PASSWORD_HINTS) return FieldKind.PASSWORD
            if (normalized in USERNAME_HINTS) return FieldKind.USERNAME
        }
        return null
    }

    private fun fromHtml(signals: FieldSignals): FieldKind? {
        if (!signals.htmlTag.equals("input", ignoreCase = true)) return null
        val attributes = signals.htmlAttributes
        val type = attributes["type"]?.lowercase(Locale.ROOT)
        if (type == "password") return FieldKind.PASSWORD
        // Anything that cannot hold a username — a checkbox, a submit button,
        // a date — is not one, whatever it is called.
        if (type != null && type !in TEXTUAL_INPUT_TYPES) return null
        val identity = listOfNotNull(
            attributes["autocomplete"],
            attributes["name"],
            attributes["id"],
        ).joinToString(" ").lowercase(Locale.ROOT)
        return if (USERNAME_WORDS.any { it in identity }) FieldKind.USERNAME else null
    }

    private fun fromInputType(signals: FieldSignals): FieldKind? {
        val inputType = signals.inputType
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return null
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> FieldKind.PASSWORD

            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> FieldKind.USERNAME

            else -> null
        }
    }

    /**
     * Both sets are compared against a lowercased hint, so they are lowercased
     * too — `View.AUTOFILL_HINT_EMAIL_ADDRESS` is `"emailAddress"`, which would
     * otherwise never match. The extra spellings are the HTML `autocomplete`
     * tokens browsers pass through alongside the Android ones.
     */
    private val PASSWORD_HINTS = setOf(
        View.AUTOFILL_HINT_PASSWORD,
        "new-password",
        "newpassword",
        "current-password",
        "currentpassword",
    ).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    private val USERNAME_HINTS = setOf(
        View.AUTOFILL_HINT_USERNAME,
        View.AUTOFILL_HINT_EMAIL_ADDRESS,
        "user-name",
        "email",
    ).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    /** `<input>` types that can hold a username. An absent type means `text`. */
    private val TEXTUAL_INPUT_TYPES = setOf("text", "email", "tel", "url", "")

    private val USERNAME_WORDS = listOf(
        "username", "user_name", "user-name", "userid", "user_id",
        "email", "e-mail", "login", "account", "identifier", "user",
    )
}
