package nu.staldal.pw.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copying a secret to the clipboard, and taking it off again.
 *
 * Desktop `pw` waits `--clear-timeout` seconds and then overwrites the
 * clipboard, but only if it still holds what pw put there. Android does not
 * let an app in the background read the clipboard, so "is it still ours?" is
 * answered by watching for a change instead of by reading: anything the user
 * copies in the meantime cancels the clear.
 *
 * The same caveat as on the desktop applies, and more strongly: a clipboard
 * history manager may keep its own copy that pw cannot reach. On API 33 and
 * later the clip is flagged sensitive, which keeps it out of the system's
 * clipboard preview and history.
 */
object Clipboard {

    /** Mirrors the setting; 0 leaves the clipboard untouched. */
    @Volatile
    var clearAfterSeconds: Int = 0

    private var pendingClear: Job? = null

    fun copySensitive(context: Context, label: String, text: String, scope: CoroutineScope) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
            }
        }
        manager.setPrimaryClip(clip)

        pendingClear?.cancel()
        val seconds = clearAfterSeconds
        if (seconds <= 0) return

        pendingClear = scope.launch {
            // The listener tells us whether anything else was copied in the
            // meantime; without it we would clobber the user's own clipboard.
            var superseded = false
            val listener = ClipboardManager.OnPrimaryClipChangedListener { superseded = true }
            manager.addPrimaryClipChangedListener(listener)
            try {
                delay(seconds * 1000L)
                if (!superseded) clear(manager)
            } finally {
                manager.removePrimaryClipChangedListener(listener)
            }
        }
    }

    /** Take a copied secret off the clipboard now, e.g. when the vault locks. */
    fun clearNow(context: Context) {
        pendingClear?.cancel()
        pendingClear = null
        context.getSystemService(ClipboardManager::class.java)?.let(::clear)
    }

    private fun clear(manager: ClipboardManager) {
        // Overwrite before clearing: a clipboard manager that keeps history
        // notices the new content, where an empty clipboard may just leave the
        // previous entry showing. This is what desktop pw does too.
        manager.setPrimaryClip(ClipData.newPlainText(null, " "))
        manager.clearPrimaryClip()
    }

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE`, which became public API in
     * API 33 but has had this value since well before that, and which several
     * keyboards and clipboard managers already honoured. Spelled out so one
     * line covers every release this app runs on.
     */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}
