package nu.staldal.mypass.autofill

import android.view.autofill.AutofillId

internal data class EnrollmentDestination(
    val packageName: String,
    val identity: Browsers.Identity,
    val host: String,
    val usernameId: AutofillId?,
    val passwordId: AutofillId,
    val focusedId: AutofillId?,
    val compatibilityMode: Boolean,
)

internal object BrowserEnrollmentRequests {
    private val store = ExpiringRequestStore<EnrollmentDestination>()
    fun register(destination: EnrollmentDestination) = store.register(destination)
    fun get(token: String) = store.get(token)
    fun take(token: String) = store.take(token)
    fun cancel(token: String) = store.cancel(token)
}
