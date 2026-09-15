package nu.staldal.pw.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.view.autofill.AutofillId
import nu.staldal.pw.PwApplication

internal object AutofillAuthRequests {
    val store = AuthRequestStore<AutofillId>()

    /** Read publisher certificates and enrollment again at the release boundary. */
    fun accepts(context: Context, destination: AuthDestination<AutofillId>,
                structure: AssistStructure, form: ParsedForm): Boolean {
        val packageName = structure.activityComponent?.packageName
        val identity = packageName?.let { BrowserCertificates.read(context.packageManager, it) }
        val app = context.applicationContext as PwApplication
        val trusted = Browsers.isTrustedBrowser(packageName, identity, app.browserCertificatePins)
        return destination.accepts(packageName, trusted, form.webScheme, form.webDomain,
            form.usernameId, form.passwordId)
    }
}
