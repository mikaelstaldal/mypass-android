package nu.staldal.pw.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nu.staldal.pw.PwApplication
import nu.staldal.pw.data.Settings
import nu.staldal.pw.data.SettingsState

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings: Settings = (application as PwApplication).settings

    val state: StateFlow<SettingsState> = settings.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    fun setAutoLockMinutes(value: Int) = edit { settings.setAutoLockMinutes(value) }

    fun setLockOnBackground(value: Boolean) = edit { settings.setLockOnBackground(value) }

    fun setClipboardClearSeconds(value: Int) = edit { settings.setClipboardClearSeconds(value) }

    fun setPasswordLength(value: Int) = edit { settings.setPasswordLength(value) }

    fun setPasswordCharset(value: String) = edit { settings.setPasswordCharset(value) }

    fun setScryptLogN(value: Int) = edit { settings.setScryptLogN(value) }

    fun setBrowserCertificatePins(value: String) = edit { settings.setBrowserCertificatePins(value) }

    fun setAutofillDiagnostics(value: Boolean) = edit { settings.setAutofillDiagnostics(value) }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
