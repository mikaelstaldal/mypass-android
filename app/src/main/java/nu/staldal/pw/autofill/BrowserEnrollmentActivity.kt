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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import nu.staldal.pw.PwApplication
import nu.staldal.pw.ui.theme.PwTheme

/** User confirmation for granting an otherwise unknown browser authority over web origins. */
class BrowserEnrollmentActivity : FragmentActivity() {
    private lateinit var token: String
    private lateinit var destination: EnrollmentDestination
    private lateinit var structure: AssistStructure
    private var deciding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val candidateToken = intent.data
            ?.takeIf { it.scheme == "pw-browser-enrollment" && it.authority == "request" }
            ?.pathSegments?.singleOrNull()
        val candidateDestination = candidateToken?.let(BrowserEnrollmentRequests::get)
        val candidateStructure: AssistStructure? = IntentCompat.getParcelableExtra(
            intent, AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        if (candidateToken == null || candidateDestination == null || candidateStructure == null ||
            !accepts(candidateDestination, candidateStructure)) {
            finish()
            return
        }
        token = candidateToken
        destination = candidateDestination
        structure = candidateStructure

        setContent {
            PwTheme {
                AlertDialog(
                    onDismissRequest = { if (!deciding) finish() },
                    title = { Text("Trust this browser?") },
                    text = {
                        Text(buildString {
                            val label = runCatching {
                                packageManager.getApplicationLabel(
                                    packageManager.getApplicationInfo(destination.packageName, 0))
                            }.getOrNull()
                            if (label != null) append("App: $label\n")
                            append("Package: ${destination.packageName}\n")
                            append("Requesting site: ${destination.host}\n\n")
                            append("SHA-256 signing certificate")
                            if (destination.identity.current.size != 1) append("s")
                            append(":\n")
                            append(destination.identity.current.sorted().joinToString("\n"))
                            append("\n\nOnly continue if you trust this app and its publisher. ")
                            append("Trusting it allows the browser to request passwords for any website.")
                            if (destination.packageName in Browsers.KNOWN) {
                                append("\n\nWarning: this package name belongs to a built-in browser, ")
                                append("but its publisher certificate does not match pw's pin.")
                            }
                        })
                    },
                    confirmButton = { TextButton(onClick = { decide(true) }) { Text("Trust") } },
                    dismissButton = { TextButton(onClick = { decide(false) }) { Text("Don't trust") } },
                )
            }
        }
    }

    private fun accepts(destination: EnrollmentDestination, structure: AssistStructure): Boolean {
        val packageName = structure.activityComponent?.packageName
        val identity = packageName?.let { BrowserCertificates.read(packageManager, it) }
        val form = AutofillStructureParser.parse(structure, destination.focusedId)
        return packageName == destination.packageName && identity == destination.identity &&
            MatchingHost.accepts(destination, form)
    }

    private fun decide(trust: Boolean) {
        if (deciding) return
        deciding = true
        val request = BrowserEnrollmentRequests.take(token) ?: run { finish(); return }
        if (request != destination || !accepts(request, structure)) { finish(); return }
        val app = application as PwApplication
        app.applicationScope.launch {
            if (trust) {
                app.trustBrowser(request.packageName, request.identity)
                respond(app, request)
            } else {
                app.rejectBrowser(request.packageName, request.identity)
                finish()
            }
        }
    }

    private fun respond(app: PwApplication, request: EnrollmentDestination) {
        val form = AutofillStructureParser.parse(structure, request.focusedId)
        if (!accepts(request, structure)) { finish(); return }
        val entries = app.repository.entriesOrNull()
        val inline = inlineRequest()
        val response = if (entries != null) {
            FillResponses.forEntries(this, form, request.host, entries, inline, request.compatibilityMode)
        } else if (app.repository.vaultExists()) {
            val authToken = AutofillAuthRequests.store.register(AuthDestination(
                request.packageName, request.host, request.usernameId, request.passwordId,
                request.focusedId, request.compatibilityMode))
            FillResponses.locked(this, form, request.host, inline, authToken, request.compatibilityMode)
        } else null
        FillDiagnostics.record(
            request.packageName,
            when {
                response == null && entries == null -> FillOutcome.NO_VAULT
                response == null -> FillOutcome.NO_MATCHING_ENTRY
                entries == null -> FillOutcome.UNLOCK_OFFERED
                else -> FillOutcome.OFFERED
            },
            request.compatibilityMode, form.webScheme, request.host,
            form.diagnosis.classifiedFields,
        )
        if (response == null) {
            finish()
            return
        }
        setResult(Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
        finish()
    }

    private fun inlineRequest(): InlineRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val request: InlineSuggestionsRequest? = IntentCompat.getParcelableExtra(
            intent, AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST,
            InlineSuggestionsRequest::class.java)
        val specs = request?.inlinePresentationSpecs ?: return null
        if (specs.isEmpty() || request.maxSuggestionCount <= 0) return null
        return InlineRequest(specs, request.maxSuggestionCount)
    }
}

private object MatchingHost {
    fun accepts(destination: EnrollmentDestination, form: ParsedForm): Boolean =
        nu.staldal.pw.data.Matching.eligibleWebHost(form.webScheme, form.webDomain) == destination.host &&
            form.usernameId == destination.usernameId && form.passwordId == destination.passwordId
}
