package nu.staldal.pw

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import nu.staldal.pw.data.PwRepository
import nu.staldal.pw.data.Settings
import nu.staldal.pw.data.SettingsState
import nu.staldal.pw.util.BiometricPassphraseStore
import nu.staldal.pw.util.Clipboard

/**
 * Process-wide state: one [PwRepository] shared by the UI and the autofill
 * service, so unlocking in one opens the vault for the other, and one
 * [Settings].
 */
class PwApplication : Application() {

    lateinit var repository: PwRepository
        private set

    lateinit var settings: Settings
        private set

    lateinit var biometrics: BiometricPassphraseStore
        private set

    /**
     * An application-lifetime scope for work that must outlive the screen that
     * started it — clearing the clipboard, above all.
     */
    val applicationScope = CoroutineScope(SupervisorJob())

    /** Mirrors [SettingsState.browserCertificatePins] for the autofill service. */
    @Volatile
    var browserCertificatePins: String = ""
        private set

    @Volatile
    private var lockOnBackground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        biometrics = BiometricPassphraseStore(this)
        repository = PwRepository(
            filesDir, SystemClock::elapsedRealtime, applicationScope,
            onReplacementCommitted = biometrics::clear,
        )
        settings = Settings(this)
        // Autofill can arrive before the collector emits. Load the small local
        // preference blob before publishing this application to the service.
        browserCertificatePins = runBlocking { settings.state.first().browserCertificatePins }

        applicationScope.launch {
            settings.state.collect { state ->
                repository.autoLockMinutes = state.autoLockMinutes
                repository.scryptLogN = state.scryptLogN
                browserCertificatePins = state.browserCertificatePins
                lockOnBackground = state.lockOnBackground
                Clipboard.clearAfterSeconds = state.clipboardClearSeconds
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // The auto-lock timer runs regardless; this is the stricter
                // opt-in that also relocks the moment pw leaves the screen.
                if (lockOnBackground) repository.lock()
                repository.enforceAutoLock()
            }

            override fun onStart(owner: LifecycleOwner) {
                repository.enforceAutoLock()
            }
        })
    }
}
