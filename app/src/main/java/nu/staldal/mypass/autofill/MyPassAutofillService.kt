package nu.staldal.mypass.autofill

import android.annotation.SuppressLint
import android.app.assist.AssistStructure
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import nu.staldal.mypass.R
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import nu.staldal.mypass.MyPassApplication
import nu.staldal.mypass.data.Matching

/**
 * The Android counterpart of desktop `MyPass`'s Firefox native-messaging host.
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
 *   change the vault format that desktop `MyPass` shares.
 * - **`https:` only**, plus `http://localhost` and `http://127.0.0.1` for
 *   local development.
 * - **Only from a browser.** The `webDomain` in a fill request is whatever the
 *   source app put there, so it counts only from a certificate-authenticated publisher in [Browsers].
 * - **Origin matching** is [Matching.matchingEntries]: exact host, or a parent
 *   domain at a label boundary, bounded by the Public Suffix List.
 * - **Read-only.** This service never writes the vault; a save request hands
 *   off to [AutofillSaveActivity], where the user confirms.
 */
class MyPassAutofillService : AutofillService() {

    private val app: MyPassApplication get() = application as MyPassApplication

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        // Deliberately unguarded by an SDK check, which is why lint's warning
        // about the inlined constant is suppressed rather than answered: the
        // flag became public API in 29, but this is a bit test against a value
        // the compiler inlines, so on 28 it reads false where the framework
        // does not set it and true where it does — which is the safe way round
        // for what depends on it. Compatibility mode is never requested (see
        // res/xml/autofill_service.xml); this only recognises one that arrives.
        @SuppressLint("InlinedApi")
        val compatibilityMode =
            request.flags and FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST != 0
        val context = request.fillContexts.lastOrNull()
        val structure = context?.structure
        if (structure == null) {
            FillDiagnostics.record(null, FillOutcome.NO_STRUCTURE, compatibilityMode)
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
                    requester, FillDiagnostics.outcomeOf(it), compatibilityMode,
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
                compatibilityMode,
                form.webScheme, form.webDomain, form.diagnosis.classifiedFields,
            )
            callback.onSuccess(null)
            return
        }

        if (!eligibility.trusted) {
            if (cancellationSignal.isCanceled) {
                callback.onSuccess(null)
                return
            }
            val packageName = requester
            val identity = eligibility.identity
            if (packageName != null && identity != null &&
                !Browsers.isRejected(packageName, identity, app.rejectedBrowserCertificates)) {
                val token = BrowserEnrollmentRequests.register(EnrollmentDestination(
                    packageName, identity, host, form.usernameId, requireNotNull(form.passwordId),
                    focusedId, compatibilityMode))
                if (cancellationSignal.isCanceled) {
                    BrowserEnrollmentRequests.cancel(token)
                    callback.onSuccess(null)
                    return
                }
                FillDiagnostics.record(requester, FillOutcome.ENROLLMENT_OFFERED, compatibilityMode,
                    form.webScheme, host, form.diagnosis.classifiedFields)
                callback.onSuccess(FillResponses.browserEnrollment(this, form, packageName, token))
            } else {
                FillDiagnostics.record(requester, FillOutcome.UNTRUSTED_BROWSER, compatibilityMode,
                    form.webScheme, host, form.diagnosis.classifiedFields)
                callback.onSuccess(null)
            }
            return
        }

        if (cancellationSignal.isCanceled) {
            recordCancellation(requester, compatibilityMode, form, host)
            callback.onSuccess(null)
            return
        }
        var authToken: String? = null
        val inline = InlineRequest.from(request)
        val entries = app.repository.entriesOrNull()
        val response = when {
            entries != null ->
                FillResponses.forEntries(this, form, host, entries, inline, compatibilityMode)
            // No vault yet: offer nothing rather than an unlock prompt for a
            // vault that does not exist.
            app.repository.vaultExists() -> {
                // eligibleHost and the passwordId check above established these.
                val token = AutofillAuthRequests.store.register(AuthDestination(
                    requireNotNull(structure.activityComponent).packageName, host, form.usernameId,
                    requireNotNull(form.passwordId), focusedId, compatibilityMode))
                authToken = token
                FillResponses.locked(this, form, host, inline, token, compatibilityMode)
            }
            else -> null
        }
        // The signal belongs to the fill computation, not to the lifetime
        // of a published offer or its authentication activity.
        if (cancellationSignal.isCanceled) {
            authToken?.let { AutofillAuthRequests.store.cancel(it) }
            recordCancellation(requester, compatibilityMode, form, host)
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
                compatibilityMode,
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
     * A request the framework withdrew while MyPass was answering it. Not a
     * refusal, but it has to be recorded for the same reason the refusals are:
     * the user sees nothing appear, and "MyPass declined" and "MyPass never got to
     * finish" have different fixes. Tapping a field is exactly when a
     * cancellation is likely — the keyboard arrives, the page reflows, and the
     * framework reissues the request — so without this the most interesting
     * case looks identical to the browser never asking at all.
     *
     * It closes the gap only as far as the two checkpoints around the fill
     * computation: a request cancelled before this service was called, or
     * between the checks, still leaves no record. Nothing here can see that.
     */
    private fun recordCancellation(
        requester: String?,
        compatibilityMode: Boolean,
        form: ParsedForm,
        host: String,
    ) = FillDiagnostics.record(
        requester, FillOutcome.REQUEST_CANCELLED, compatibilityMode,
        form.webScheme, host, form.diagnosis.classifiedFields,
    )

    /**
     * The page's hostname when this request may be answered at all: a trusted
     * browser, reporting an eligible scheme and a usable host.
     */
    private fun eligibleHost(structure: AssistStructure, form: ParsedForm): String? =
        eligibility(structure, form).takeIf { it.trusted }?.host

    /**
     * [eligibleHost], with the publisher check kept visible: an untrusted
     * requester and an ineligible origin are two different refusals, and the
     * diagnostic needs to tell them apart. Certificates are read once, on this
     * path only, exactly as before.
     */
    private fun eligibility(structure: AssistStructure, form: ParsedForm): Eligibility {
        val packageName = structure.activityComponent?.packageName
        val identity = packageName?.let { BrowserCertificates.read(packageManager, it) }
        val host = Matching.eligibleWebHost(form.webScheme, form.webDomain)
        val trusted = Browsers.isTrustedBrowser(packageName, identity, app.browserCertificatePins)
        return Eligibility(trusted, host, identity)
    }

    private data class Eligibility(
        val trusted: Boolean,
        val host: String?,
        val identity: Browsers.Identity?,
    )

    private companion object {
        const val REQUEST_CODE_SAVE = 3
    }
}
