package nu.staldal.mypass.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import nu.staldal.mypass.MyPassApplication
import nu.staldal.mypass.data.PasswordGenerator
import nu.staldal.mypass.data.MyPassException
import nu.staldal.mypass.data.MyPassRepository
import nu.staldal.mypass.data.SettingsState
import nu.staldal.mypass.data.VaultState
import nu.staldal.mypass.util.Clipboard
import nu.staldal.mypass.vault.PasswordEntry
import nu.staldal.mypass.vault.Secret

/**
 * The screen-facing side of [MyPassRepository]: the same operations, but
 * suspending work moved off the UI thread, failures turned into a message to
 * show, and a busy flag for the seconds the scrypt KDF takes.
 */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyPassApplication
    private val repository: MyPassRepository = app.repository
    val biometrics = app.biometrics

    val state: StateFlow<VaultState> = repository.state

    val settings: StateFlow<SettingsState> = app.settings.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-shot text for a snackbar; cleared by [messageShown]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    fun show(text: String) {
        _message.value = text
    }

    fun vaultExists(): Boolean = repository.vaultExists()

    fun lock() {
        repository.lock()
    }

    fun createVault(passphrase: String, onSuccess: () -> Unit) =
        run(onSuccess) { repository.createVault(passphrase) }

    fun unlock(passphrase: String, onSuccess: () -> Unit) =
        run(onSuccess) { repository.unlock(passphrase) }

    fun verifyBiometricEnrollment(passphrase: String, onVerified: (MyPassRepository.Enrollment) -> Unit) =
        run({}) { onVerified(repository.verifyBiometricEnrollment(passphrase)) }

    fun completeBiometricEnrollment(
        token: MyPassRepository.Enrollment,
        store: () -> Boolean,
        onSuccess: () -> Unit,
    ) {
        if (_busy.value) {
            show("The vault is busy; try enabling fingerprint unlock again.")
            return
        }
        run({}) {
            if (repository.completeBiometricEnrollment(token, biometrics::clear, store)) onSuccess()
            else show("Could not store the passphrase.")
        }
    }

    fun add(entry: PasswordEntry, onSuccess: () -> Unit) =
        run(onSuccess) { repository.add(entry) }

    fun update(name: String, entry: PasswordEntry, onSuccess: () -> Unit) =
        run(onSuccess) { repository.update(name, entry) }

    fun remove(name: String, onSuccess: () -> Unit) =
        run(onSuccess) { repository.remove(name) }

    fun changePassphrase(newPassphrase: String, onSuccess: () -> Unit) =
        run(onSuccess) { repository.changePassphrase(newPassphrase) }

    fun importVault(openInput: () -> InputStream?, passphrase: String, onSuccess: () -> Unit) =
        run(onSuccess) {
            withContext(Dispatchers.IO) {
                val input = openInput() ?: throw MyPassException.InvalidInput("file", "could not read the file you picked")
                input.use { repository.importVault(it, passphrase) }
            }
        }

    fun importKeePass(openInput: () -> InputStream?, passphrase: String, onSuccess: () -> Unit) =
        run(onSuccess) {
            withContext(Dispatchers.IO) {
                val input = openInput() ?: throw MyPassException.InvalidInput("file", "could not read the file you picked")
                input.use { repository.importKeePass(it, passphrase) }
            }
        }

    fun exportVault(out: OutputStream, onSuccess: () -> Unit) =
        run(onSuccess) { out.use { repository.copyVaultTo(it) } }

    fun exportJson(out: OutputStream, onSuccess: () -> Unit) = run(onSuccess) {
        out.use { it.write(repository.exportJson().toByteArray(Charsets.UTF_8)) }
    }

    fun entry(name: String): PasswordEntry? =
        (state.value as? VaultState.Unlocked)?.entries?.find { it.name == name }

    fun generatePassword(length: Int, charset: String): Secret? = try {
        PasswordGenerator.generate(length, charset)
    } catch (e: MyPassException) {
        _message.value = e.message
        null
    }

    fun copyToClipboard(label: String, text: String, confirmation: String) {
        Clipboard.copySensitive(getApplication(), label, text, app.applicationScope)
        _message.value = confirmation
    }

    /**
     * Run one repository operation: busy while it works, its message shown if
     * it fails, [onSuccess] only if it does not.
     */
    private fun run(onSuccess: () -> Unit, block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MyPassException.ReplacementCommitted) {
                _message.value = e.message
            } catch (e: MyPassException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = false
            }
        }
    }
}
