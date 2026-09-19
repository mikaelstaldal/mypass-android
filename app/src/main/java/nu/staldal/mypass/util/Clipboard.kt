package nu.staldal.mypass.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.annotation.MainThread
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nu.staldal.mypass.data.SettingsState

/**
 * Copying a secret to the clipboard, and taking it off again.
 *
 * Desktop `MyPass` waits `--clear-timeout` seconds and then overwrites the
 * clipboard, but only if it still holds what MyPass put there. Android does not
 * let an app in the background read the clipboard. While MyPass is foregrounded,
 * a listener and a private tag in the clip description verify that the clip is
 * still ours. A clear requested in the background is deferred until MyPass returns
 * to the foreground and can verify the tag, rather than risking overwriting a
 * newer clip that Android did not tell us about.
 *
 * All public operations and mutable state are confined to the main thread.
 *
 * The same caveat as on the desktop applies, and more strongly: a clipboard
 * history manager may keep its own copy that MyPass cannot reach. On API 33 and
 * later the clip is flagged sensitive, which keeps it out of the system's
 * clipboard preview and history.
 */
object Clipboard {

    /** Mirrors the setting; 0 disables timed clearing but lock still clears. */
    @get:MainThread
    @set:MainThread
    var clearAfterSeconds: Int = SettingsState.DEFAULT_CLIPBOARD_CLEAR_SECONDS

    private var pendingClear: Job? = null
    private var ownedManager: ClipboardManager? = null
    private var ownershipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var ownedToken: Long = 0
    private var foreground: Boolean = false
    private var clearWhenForeground: Boolean = false
    private var verificationRetries: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val retryClear = Runnable(::clearIfOwned)

    @MainThread
    fun copySensitive(context: Context, label: String, text: String, scope: CoroutineScope) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val token = random.nextLong()
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
                putLong(EXTRA_OWNERSHIP_TOKEN, token)
            }
        }
        forgetOwnership()
        manager.setPrimaryClip(clip)

        // Install ownership tracking synchronously, before the timer is
        // launched. Launching first leaves a window in which another clip can
        // replace ours before the coroutine gets to register its listener.
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            primaryClipChanged(manager, token)
        }
        ownedManager = manager
        ownedToken = token
        ownershipListener = listener
        manager.addPrimaryClipChangedListener(listener)

        val seconds = clearAfterSeconds
        if (seconds <= 0) return

        pendingClear = scope.launch {
            delay(seconds * 1000L)
            clearIfOwned()
        }
    }

    /** Take our copied secret off the clipboard, without touching a later clip. */
    @MainThread
    fun clearIfOwned() {
        val manager = ownedManager ?: return
        pendingClear?.cancel()
        pendingClear = null
        if (!foreground) {
            clearWhenForeground = true
            return
        }
        clearWhenForeground = false
        when (isCurrentClipOurs(manager, ownedToken)) {
            null -> {
                // The lifecycle can reach ON_START just before clipboard read
                // access is restored. Retry briefly while the window gains
                // focus, then retain the request for the next lifecycle edge.
                clearWhenForeground = true
                if (verificationRetries++ < MAX_VERIFICATION_RETRIES) {
                    mainHandler.postDelayed(retryClear, VERIFICATION_RETRY_MILLIS)
                }
                return
            }
            false -> {
                forgetOwnership()
                return
            }
            true -> Unit
        }
        // Forget first: clearing the platform clipboard itself emits a change.
        forgetOwnership()
        clear(manager)
    }

    /** Clipboard reads and change callbacks are reliable only while focused. */
    @MainThread
    fun setForeground(value: Boolean) {
        foreground = value
        mainHandler.removeCallbacks(retryClear)
        verificationRetries = 0
        if (value && clearWhenForeground) clearIfOwned()
    }

    private fun primaryClipChanged(manager: ClipboardManager, token: Long) {
        // A callback already posted for an old listener may arrive after a
        // rapid second copy. It must not discard ownership of the newer clip.
        if (manager !== ownedManager || token != ownedToken) return
        if (isCurrentClipOurs(manager, token) == false) forgetOwnership()
    }

    /** `null` means Android did not expose a current description to verify. */
    private fun isCurrentClipOurs(manager: ClipboardManager, token: Long): Boolean? {
        val description = manager.primaryClipDescription ?: return null
        val extras = description.extras ?: return false
        return extras.containsKey(EXTRA_OWNERSHIP_TOKEN) &&
            extras.getLong(EXTRA_OWNERSHIP_TOKEN) == token
    }

    private fun forgetOwnership() {
        pendingClear?.cancel()
        pendingClear = null
        val manager = ownedManager
        val listener = ownershipListener
        ownedManager = null
        ownershipListener = null
        ownedToken = 0
        clearWhenForeground = false
        verificationRetries = 0
        mainHandler.removeCallbacks(retryClear)
        if (manager != null && listener != null) {
            manager.removePrimaryClipChangedListener(listener)
        }
    }

    private fun clear(manager: ClipboardManager) {
        // Overwrite before clearing: a clipboard manager that keeps history
        // notices the new content, where an empty clipboard may just leave the
        // previous entry showing. This is what desktop MyPass does too.
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
    private const val EXTRA_OWNERSHIP_TOKEN = "nu.staldal.mypass.extra.CLIP_TOKEN"
    private const val MAX_VERIFICATION_RETRIES = 5
    private const val VERIFICATION_RETRY_MILLIS = 200L
}
