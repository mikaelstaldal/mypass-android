package nu.staldal.mypass

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nu.staldal.mypass.autofill.FillDiagnostics
import nu.staldal.mypass.autofill.Browsers
import nu.staldal.mypass.data.MyPassRepository
import nu.staldal.mypass.data.Settings
import nu.staldal.mypass.data.SettingsState
import nu.staldal.mypass.data.VaultState
import nu.staldal.mypass.util.BiometricPassphraseStore
import nu.staldal.mypass.util.Clipboard

/**
 * Process-wide state: one [MyPassRepository] shared by the UI and the autofill
 * service, so unlocking in one opens the vault for the other, and one
 * [Settings].
 */
class MyPassApplication : Application() {

    lateinit var repository: MyPassRepository
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
    var rejectedBrowserCertificates: String = ""
        private set

    @Volatile
    private var lockOnBackground: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val browserDecisionLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        biometrics = BiometricPassphraseStore(this)
        repository = MyPassRepository(
            filesDir, SystemClock::elapsedRealtime, applicationScope,
            onReplacementCommitted = biometrics::clear,
        )
        settings = Settings(this)
        // Autofill can arrive before the collector emits. Load the small local
        // preference blob before publishing this application to the service.
        val initial = runBlocking { settings.state.first() }
        browserCertificatePins = initial.browserCertificatePins
        rejectedBrowserCertificates = initial.rejectedBrowserCertificates
        FillDiagnostics.setDiagnosticSink { record ->
            Log.i(AUTOFILL_DIAGNOSTIC_TAG, FillDiagnostics.logMessage(record))
        }
        FillDiagnostics.setEnabled(initial.autofillDiagnostics)

        applicationScope.launch {
            settings.state.collect { state ->
                repository.autoLockMinutes = state.autoLockMinutes
                repository.scryptLogN = state.scryptLogN
                browserCertificatePins = state.browserCertificatePins
                rejectedBrowserCertificates = state.rejectedBrowserCertificates
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
                // opt-in that also relocks the moment MyPass leaves the screen.
                if (lockOnBackground) repository.lock()
                repository.enforceAutoLock()
            }

            override fun onStart(owner: LifecycleOwner) {
                Clipboard.setForeground(true)
                repository.enforceAutoLock()
            }
        })
    }


    suspend fun trustBrowser(packageName: String, identity: Browsers.Identity) = browserDecisionLock.withLock {
        val value = Browsers.enroll(browserCertificatePins, packageName, identity)
        settings.setBrowserCertificatePins(value)
        browserCertificatePins = value
        val rejected = Browsers.remove(rejectedBrowserCertificates, packageName)
        settings.setRejectedBrowserCertificates(rejected)
        rejectedBrowserCertificates = rejected
    }

    suspend fun rejectBrowser(packageName: String, identity: Browsers.Identity) = browserDecisionLock.withLock {
        val value = Browsers.reject(rejectedBrowserCertificates, packageName, identity)
        settings.setRejectedBrowserCertificates(value)
        rejectedBrowserCertificates = value
    }

    private companion object {
        const val AUTOFILL_DIAGNOSTIC_TAG = "mypass-autofill"
    }
}
