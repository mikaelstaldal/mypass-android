package nu.staldal.pw.autofill

import android.os.Build
import android.service.autofill.FillRequest
import android.widget.inline.InlinePresentationSpec

/**
 * The keyboard's offer to show suggestions inline, reduced to what this app
 * needs from it. Inline suggestions arrived in API 30; below that this is
 * always `null` and the framework falls back to the dropdown built from the
 * [android.widget.RemoteViews] presentations.
 */
class InlineRequest(
    val specs: List<InlinePresentationSpec>,
    val maxSuggestionCount: Int,
) {
    companion object {
        fun from(request: FillRequest): InlineRequest? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val inline = request.inlineSuggestionsRequest ?: return null
            val specs = inline.inlinePresentationSpecs
            if (specs.isEmpty() || inline.maxSuggestionCount <= 0) return null
            return InlineRequest(specs, inline.maxSuggestionCount)
        }
    }
}
