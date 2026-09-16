package nu.staldal.pw.autofill

import android.app.assist.AssistStructure
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import nu.staldal.pw.R
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import nu.staldal.pw.PwApplication
import nu.staldal.pw.data.Matching

/**
 * The Android counterpart of desktop `pw`'s Firefox native-messaging host.
 *
 * It fills usernames and passwords into login forms **without using the
 * clipboard** and **without handing the browser the whole vault**: it decrypts
 * in this app's own process, matches the page against the entries' `url`
 * fields, and releases only the entries for that site.
 *
 * The rules it enforces are the desktop host's:
 *
 * - **Web pages only.** A request must carry a web origin, so native app
 *   screens are never filled. An entry's association with a site is its `url`,
 *   which has no meaning for an app, and inventing a second association would
 *   change the vault format that desktop `pw` shares.
 * - **`https:` only**, plus `http://localhost` and `http://127.0.0.1` for
 *   local development.
 * - **Only from a browser.** The `webDomain` in a fill request is whatever the
 *   source app put there, so it counts only from a certificate-authenticated publisher in [Browsers].
 * - **Origin matching** is [Matching.matchingEntries]: exact host, or a parent
 *   domain at a label boundary, bounded by the Public Suffix List.
 * - **Read-only.** This service never writes the vault; a save request hands
 *   off to [AutofillSaveActivity], where the user confirms.
 */
class PwAutofillService : AutofillService() {

    private val app: PwApplication get() = application as PwApplication

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val context = request.fillContexts.lastOrNull()
        val structure = context?.structure
        if (structure == null) {
            FillDiagnostics.record(null, FillOutcome.NO_STRUCTURE)
            callback.onSuccess(null)
            return
        }
        val requester = structure.activityComponent?.packageName
        val focusedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.focusedId else null
        val form = AutofillStructureParser.parse(structure, focusedId)
        if (form.passwordId == null) {
            // Nothing to fill a password into: not a login form. The selector
            // always names the rule it refused on, so an absent reason records
            // nothing rather than inventing one.
            val diagnosis = form.diagnosis
            diagnosis.refusal?.let {
                FillDiagnostics.record(
                    requester, FillDiagnostics.outcomeOf(it),
                    diagnosis.focusedScheme, diagnosis.focusedHost, diagnosis.classifiedFields,
                )
            }
            callback.onSuccess(null)
            return
        }
        val eligibility = eligibility(structure, form)
        val host = eligibility.host
        if (host == null) {
            FillDiagnostics.record(
                requester,
                if (eligibility.trusted) FillOutcome.INELIGIBLE_ORIGIN
                else FillOutcome.UNTRUSTED_BROWSER,
                form.webScheme, form.webDomain, form.diagnosis.classifiedFields,
            )
            callback.onSuccess(null)
            return
        }

        if (cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }
        var authToken: String? = null
        val inline = InlineRequest.from(request)
        val entries = app.repository.entriesOrNull()
        val response = when {
            entries != null -> FillResponses.forEntries(this, form, host, entries, inline)
            // No vault yet: offer nothing rather than an unlock prompt for a
            // vault that does not exist.
            app.repository.vaultExists() -> {
                // eligibleHost and the passwordId check above established these.
                val token = AutofillAuthRequests.store.register(AuthDestination(
                    requireNotNull(structure.activityComponent).packageName, host, form.usernameId,
                    requireNotNull(form.passwordId), focusedId))
                authToken = token
                FillResponses.locked(this, form, host, inline, token)
            }
            else -> null
        }
        // The signal belongs to the fill computation, not to the lifetime
        // of a published offer or its authentication activity.
        if (cancellationSignal.isCanceled) {
            authToken?.let { AutofillAuthRequests.store.cancel(it) }
            callback.onSuccess(null)
        } else {
            FillDiagnostics.record(
                requester,
                when {
                    response == null && entries == null -> FillOutcome.NO_VAULT
                    response == null -> FillOutcome.NO_MATCHING_ENTRY
                    entries == null -> FillOutcome.UNLOCK_OFFERED
                    else -> FillOutcome.OFFERED
                },
                form.webScheme, host, form.diagnosis.classifiedFields,
            )
            callback.onSuccess(response)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val context = request.fillContexts.lastOrNull()
        val structure = context?.structure
        if (structure == null) {
            callback.onFailure(getString(R.string.autofill_save_unavailable))
            return
        }
        val focusedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.focusedId else null
        val form = AutofillStructureParser.parse(structure, focusedId)
        val host = eligibleHost(structure, form)
        val password = form.passwordValue
        if (host == null || password.isNullOrEmpty() || !app.repository.vaultExists()) {
            callback.onFailure(getString(R.string.autofill_save_unavailable))
            return
        }

        // Saving is a write, so it happens in the app, with the vault open and
        // the user looking at what is about to be stored. The service itself
        // never writes.
        val intent = Intent(this, AutofillSaveActivity::class.java)
            .putExtra(AutofillSaveActivity.EXTRA_HOST, host)
            .putExtra(AutofillSaveActivity.EXTRA_USERNAME, form.usernameValue.orEmpty())
            .putExtra(AutofillSaveActivity.EXTRA_PASSWORD, password)
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_SAVE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        callback.onSuccess(pendingIntent.intentSender)
    }

    /**
     * The page's hostname when this request may be answered at all: a trusted
     * browser, reporting an eligible scheme and a usable host.
     */
    private fun eligibleHost(structure: AssistStructure, form: ParsedForm): String? =
        eligibility(structure, form).host

    /**
     * [eligibleHost], with the publisher check kept visible: an untrusted
     * requester and an ineligible origin are two different refusals, and the
     * diagnostic needs to tell them apart. Certificates are read once, on this
     * path only, exactly as before.
     */
    private fun eligibility(structure: AssistStructure, form: ParsedForm): Eligibility {
        val packageName = structure.activityComponent?.packageName
        val identity = packageName?.let { BrowserCertificates.read(packageManager, it) }
        if (!Browsers.isTrustedBrowser(packageName, identity, app.browserCertificatePins)) {
            return Eligibility(trusted = false, host = null)
        }
        return Eligibility(trusted = true, host = Matching.eligibleWebHost(form.webScheme, form.webDomain))
    }

    private data class Eligibility(val trusted: Boolean, val host: String?)

    private companion object {
        const val REQUEST_CODE_SAVE = 3
    }
}
