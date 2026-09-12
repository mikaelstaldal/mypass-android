package nu.staldal.pw.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId
import java.util.Locale

/** A login form as this app understands it, extracted from an [AssistStructure]. */
data class ParsedForm(
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    /** Scheme of the page the password field is on, as the browser reports it. */
    val webScheme: String?,
    /** Host of the page the password field is on, as the browser reports it. */
    val webDomain: String?,
    /** Current field contents, for a save request. */
    val usernameValue: String?,
    val passwordValue: String?,
) {
    val autofillIds: Array<AutofillId>
        get() = listOfNotNull(usernameId, passwordId).toTypedArray()
}

/**
 * Walks an [AssistStructure] and collects the text fields that could belong to
 * a login form, carrying each node's web origin down from its ancestors.
 *
 * The origin is inherited rather than read from the page as a whole, because a
 * cross-origin iframe declares its own `webDomain` — so a field inside one is
 * attributed to that frame, not to the page around it.
 *
 * What each field *is* is decided by [FieldClassifier]; which pair of them is
 * the form to answer for is decided by [FormSelector]. Both are separate, and
 * free of Android types, so they can be unit-tested without a device.
 */
object AutofillStructureParser {

    fun parse(structure: AssistStructure): ParsedForm {
        val candidates = mutableListOf<FieldCandidate<AutofillId>>()
        for (i in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(i).rootViewNode, null, null, candidates)
        }
        val selected = FormSelector.select(candidates)
        return ParsedForm(
            usernameId = selected.usernameId,
            passwordId = selected.passwordId,
            webScheme = selected.webScheme,
            webDomain = selected.webDomain,
            usernameValue = selected.usernameValue,
            passwordValue = selected.passwordValue,
        )
    }

    private fun visit(
        node: AssistStructure.ViewNode,
        inheritedScheme: String?,
        inheritedDomain: String?,
        out: MutableList<FieldCandidate<AutofillId>>,
    ) {
        val scheme = node.webScheme?.takeIf { it.isNotEmpty() } ?: inheritedScheme
        val domain = node.webDomain?.takeIf { it.isNotEmpty() } ?: inheritedDomain

        val id = node.autofillId
        if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            FieldClassifier.classify(signalsOf(node))?.let { kind ->
                out += FieldCandidate(
                    kind = kind,
                    id = id,
                    scheme = scheme,
                    domain = domain,
                    value = currentValue(node),
                    focused = node.isFocused,
                )
            }
        }

        for (i in 0 until node.childCount) {
            visit(node.getChildAt(i), scheme, domain, out)
        }
    }

    private fun signalsOf(node: AssistStructure.ViewNode): FieldSignals {
        val html = node.htmlInfo
        return FieldSignals(
            autofillHints = node.autofillHints?.toList().orEmpty(),
            htmlTag = html?.tag,
            htmlAttributes = html?.attributes.orEmpty()
                .associate { it.first.lowercase(Locale.ROOT) to it.second },
            inputType = node.inputType,
        )
    }

    /**
     * What the field holds right now, for a save request. Browsers report it
     * as an `AutofillValue`; a plain Android view may only have its `text`.
     */
    private fun currentValue(node: AssistStructure.ViewNode): String? {
        val value = node.autofillValue
        if (value != null && value.isText) return value.textValue?.toString()
        return node.text?.toString()
    }
}
