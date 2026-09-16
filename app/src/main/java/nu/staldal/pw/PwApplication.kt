package nu.staldal.pw

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import nu.staldal.pw.autofill.FillDiagnostics
import nu.staldal.pw.data.PwRepository
import nu.staldal.pw.data.Settings
import nu.staldal.pw.data.SettingsState
import nu.staldal.pw.data.VaultState
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
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Mirrors [SettingsState.browserCertificatePins] for the autofill service. */
    @Volatile
    var browserCertificatePins: String = ""
        private set

    @Volatile
    private var lockOnBackground: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

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
        val initial = runBlocking { settings.state.first() }
        browserCertificatePins = initial.browserCertificatePins
        FillDiagnostics.setEnabled(initial.autofillDiagnostics)

        applicationScope.launch {
            settings.state.collect { state ->
                repository.autoLockMinutes = state.autoLockMinutes
                repository.scryptLogN = state.scryptLogN
                browserCertificatePins = state.browserCertificatePins
                FillDiagnostics.setEnabled(state.autofillDiagnostics)
                lockOnBackground = state.lockOnBackground
                Clipboard.clearAfterSeconds = state.clipboardClearSeconds
            }
        }

        applicationScope.launch {
            repository.state.collect { state ->
                if (state is VaultState.Locked) {
                    // StateFlow publishes under the repository's stateLock.
                    // Handler.post guarantees this clipboard IPC runs only
                    // after the emitter has left that monitor.
                    mainHandler.post(Clipboard::clearIfOwned)
                }
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                Clipboard.setForeground(false)
                // The auto-lock timer runs regardless; this is the stricter
                // opt-in that also relocks the moment pw leaves the screen.
                if (lockOnBackground) repository.lock()
                repository.enforceAutoLock()
            }

            override fun onStart(owner: LifecycleOwner) {
                Clipboard.setForeground(true)
                repository.enforceAutoLock()
            }
        })
    }
}
