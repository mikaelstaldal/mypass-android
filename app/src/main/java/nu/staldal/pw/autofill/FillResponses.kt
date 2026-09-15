package nu.staldal.pw.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import nu.staldal.pw.MainActivity
import nu.staldal.pw.R
import nu.staldal.pw.data.Validation
import nu.staldal.pw.data.Matching
import nu.staldal.pw.vault.PasswordEntry

/**
 * Turns matched entries into what the autofill framework wants back: a
 * [Dataset] per entry, plus the locked-vault response whose authentication
 * opens [AutofillAuthActivity].
 *
 * Shared by [PwAutofillService] and [AutofillAuthActivity], which build the
 * same response for the same request — once before the vault is open and once
 * after.
 */
@Suppress("DEPRECATION")
// The RemoteViews-taking setAuthentication/setValue overloads were deprecated
// in API 33 in favour of android.service.autofill.Presentations. They remain
// the only spelling that works on API 28..32, which this app still supports,
// and they behave identically on newer releases — so there is one code path
// rather than two.
object FillResponses {

    /**
     * The datasets for [host], or `null` when nothing matches — which the
     * framework reads as "this service has nothing to offer", leaving the
     * keyboard's own suggestions alone.
     */
    fun forEntries(
        context: Context,
        form: ParsedForm,
        host: String,
        entries: List<PasswordEntry>,
        inline: InlineRequest?,
    ): FillResponse? {
        if (form.passwordId == null) return null
        val matches = Matching.matchingEntries(host, entries)
        if (matches.isEmpty()) return null

        val builder = FillResponse.Builder()
        matches.forEachIndexed { index, entry ->
            builder.addDataset(
                dataset(
                    context = context,
                    form = form,
                    title = entry.name,
                    subtitle = entry.username.ifEmpty { host },
                    username = entry.username,
                    password = entry.password.expose(),
                    inline = inline?.takeIf { index < it.maxSuggestionCount },
                    inlineIndex = index,
                )
            )
        }
        addSaveInfo(builder, form)
        return builder.build()
    }

    /**
     * The response for a locked vault: one authentication entry that opens
     * [AutofillAuthActivity], which unlocks and returns the real datasets.
     *
     * Nothing is released here, and nothing about the vault is revealed — pw
     * cannot tell whether it has an entry for the site until it is open, so the
     * offer necessarily comes before that is known. It appears only where the
     * user focused a field, and the passphrase prompt still takes a tap.
     */
    fun locked(
        context: Context,
        form: ParsedForm,
        host: String,
        inline: InlineRequest?,
        focusedId: AutofillId? = null,
    ): FillResponse {
        val title = context.getString(R.string.autofill_unlock_title)
        val subtitle = context.getString(R.string.autofill_unlock_subtitle, host)
        val intent = Intent(context, AutofillAuthActivity::class.java)
            .putExtra(AutofillAuthActivity.EXTRA_HOST, host)
            .putExtra(AutofillAuthActivity.EXTRA_FOCUSED_ID, focusedId)
        val authPendingIntent = pendingIntent(context, intent, REQUEST_CODE_AUTH)

        val builder = FillResponse.Builder()
        val ids = form.autofillIds
        val presentation = remoteViews(context, title, subtitle)
        val inlinePresentation =
            if (inline != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                inlinePresentation(inline, 0, title, subtitle, authPendingIntent)
            } else {
                null
            }
        if (inlinePresentation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAuthentication(
                ids,
                authPendingIntent.intentSender,
                presentation,
                inlinePresentation,
            )
        } else {
            builder.setAuthentication(ids, authPendingIntent.intentSender, presentation)
        }
        addSaveInfo(builder, form)
        return builder.build()
    }

    private fun dataset(
        context: Context,
        form: ParsedForm,
        title: String,
        subtitle: String,
        username: String,
        password: String,
        inline: InlineRequest?,
        inlineIndex: Int,
    ): Dataset {
        val builder = Dataset.Builder()
        val presentation = remoteViews(context, title, subtitle)
        val inlinePresentation =
            if (inline != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                inlinePresentation(
                    inline = inline,
                    index = inlineIndex,
                    title = title,
                    subtitle = subtitle,
                    // The framework requires an attribution intent on every
                    // inline suggestion. Opening pw itself is the honest
                    // answer to "where does this suggestion come from?".
                    pendingIntent = pendingIntent(
                        context,
                        Intent(context, MainActivity::class.java),
                        REQUEST_CODE_ATTRIBUTION,
                    ),
                )
            } else {
                null
            }

        fun put(id: AutofillId?, value: String) {
            if (id == null) return
            val autofillValue = AutofillValue.forText(value)
            if (inlinePresentation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setValue(id, autofillValue, presentation, inlinePresentation)
            } else {
                builder.setValue(id, autofillValue, presentation)
            }
        }

        put(form.usernameId, username)
        put(form.passwordId, password)
        return builder.build()
    }

    /**
     * Ask the framework to offer saving what the user typed, so a login
     * created in the browser can become an entry here. Nothing is written
     * without the confirmation screen in [AutofillSaveActivity].
     */
    private fun addSaveInfo(builder: FillResponse.Builder, form: ParsedForm) {
        val passwordId = form.passwordId ?: return
        val type = if (form.usernameId != null) {
            SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        } else {
            SaveInfo.SAVE_DATA_TYPE_PASSWORD
        }
        val saveInfo = SaveInfo.Builder(type, arrayOf(passwordId))
        form.usernameId?.let { saveInfo.setOptionalIds(arrayOf(it)) }
        builder.setSaveInfo(saveInfo.build())
    }

    fun remoteViews(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_dataset).apply {
            setTextViewText(R.id.autofill_dataset_title, Validation.displayText(title))
            setTextViewText(R.id.autofill_dataset_subtitle, Validation.displayText(subtitle))
        }

    // InlineSuggestionUi builds the Slice the platform wants, but the accessor
    // for it is marked @RestrictedApi even though this is the only way the
    // library documents to obtain one.
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun inlinePresentation(
        inline: InlineRequest,
        index: Int,
        title: String,
        subtitle: String,
        pendingIntent: PendingIntent,
    ): InlinePresentation? {
        val spec = inline.specs.getOrNull(index) ?: inline.specs.lastOrNull() ?: return null
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) {
            return null
        }
        val content = InlineSuggestionUi.newContentBuilder(pendingIntent)
            .setTitle(Validation.displayText(title))
            .setSubtitle(Validation.displayText(subtitle))
            .build()
        return InlinePresentation(content.slice, spec, false)
    }

    private fun pendingIntent(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            // MUTABLE because the framework fills the launched intent in with
            // the request extras the authentication activity needs.
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private const val REQUEST_CODE_AUTH = 1
    private const val REQUEST_CODE_ATTRIBUTION = 2
}
