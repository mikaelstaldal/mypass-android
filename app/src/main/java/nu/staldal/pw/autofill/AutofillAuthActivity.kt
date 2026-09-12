package nu.staldal.pw.autofill

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.pw.PwApplication
import nu.staldal.pw.data.VaultState
import nu.staldal.pw.ui.VaultViewModel
import nu.staldal.pw.ui.screens.UnlockScreen
import nu.staldal.pw.ui.theme.PwTheme

/**
 * The passphrase prompt the autofill framework opens when a fill request finds
 * the vault locked.
 *
 * This is where the desktop host's `pinentry` dialog would be: a prompt
 * *outside* the browser, in pw's own process, which the browser cannot see or
 * drive. It answers with a [android.service.autofill.FillResponse] built from
 * the same request the service was answering, so the fill lands where the user
 * asked for it.
 */
class AutofillAuthActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Default to "the user backed out", so dismissing this screen leaves
        // the browser with no fill rather than an empty one.
        setResult(Activity.RESULT_CANCELED)

        val host = intent.getStringExtra(EXTRA_HOST)
        val structure: AssistStructure? = IntentCompat.getParcelableExtra(
            intent,
            AutofillManager.EXTRA_ASSIST_STRUCTURE,
            AssistStructure::class.java,
        )
        if (host == null || structure == null) {
            finish()
            return
        }

        val inline = inlineRequest()

        setContent {
            PwTheme {
                Surface {
                    val viewModel: VaultViewModel = viewModel()
                    val state by viewModel.state.collectAsState()
                    // Answering is driven by the vault opening rather than by
                    // this screen's own success callback, so a vault unlocked
                    // elsewhere while this was opening answers immediately too.
                    LaunchedEffect(state) {
                        if (state is VaultState.Unlocked) respond(structure, host, inline)
                    }
                    UnlockScreen(
                        viewModel = viewModel,
                        onUnlocked = {},
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        subtitle = "Fill a login for $host",
                        compact = true,
                    )
                }
            }
        }
    }

    /**
     * The keyboard's inline-suggestion request, forwarded by the framework
     * with the authentication intent, so the fill that follows an unlock can
     * still appear in the keyboard strip.
     *
     * Inline suggestions themselves are API 30, but the framework only started
     * passing the request on to the authentication activity in API 31; below
     * that the fill after an unlock falls back to the dropdown.
     */
    private fun inlineRequest(): InlineRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val request: InlineSuggestionsRequest? = IntentCompat.getParcelableExtra(
            intent,
            AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST,
            InlineSuggestionsRequest::class.java,
        )
        val specs = request?.inlinePresentationSpecs ?: return null
        if (specs.isEmpty() || request.maxSuggestionCount <= 0) return null
        return InlineRequest(specs, request.maxSuggestionCount)
    }

    private fun respond(structure: AssistStructure, host: String, inline: InlineRequest?) {
        if (isFinishing) return
        val app = application as PwApplication
        val entries = app.repository.entriesOrNull()
        if (entries == null) {
            // Still locked — the unlock failed or expired again. Leave the
            // result as cancelled.
            finish()
            return
        }
        val form = AutofillStructureParser.parse(structure)
        val response = FillResponses.forEntries(this, form, host, entries, inline)
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
        )
        finish()
    }

    companion object {
        const val EXTRA_HOST = "nu.staldal.pw.autofill.HOST"
    }
}
