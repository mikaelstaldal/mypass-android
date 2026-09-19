package nu.staldal.mypass.autofill

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.mypass.data.Validation
import nu.staldal.mypass.data.Matching
import nu.staldal.mypass.data.VaultState
import nu.staldal.mypass.ui.VaultViewModel
import nu.staldal.mypass.ui.screens.UnlockScreen
import nu.staldal.mypass.ui.theme.MyPassTheme
import nu.staldal.mypass.vault.PasswordEntry
import nu.staldal.mypass.vault.Secret

/**
 * Confirm saving a login the user just typed in a browser.
 *
 * The autofill *service* is read-only, as the desktop host is: it never writes
 * the vault. A write happens here instead, in MyPass's own UI, with the vault open
 * and the entry on screen before it is stored — so the browser can suggest
 * that something be saved, but never decide what.
 *
 * The entry is created with its `url` set to the site the password was typed
 * on, which is the one thing that will let it be filled there again.
 */
class AutofillSaveActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setResult(Activity.RESULT_CANCELED)

        val host = intent.getStringExtra(EXTRA_HOST)
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        if (host == null || password.isNullOrEmpty()) {
            finish()
            return
        }

        setContent {
            MyPassTheme {
                val viewModel: VaultViewModel = viewModel()
                val state by viewModel.state.collectAsState()
                when (val current = state) {
                    // The unlock screen fills the window, so it needs a
                    // surface to sit on; the dialog below floats over the
                    // browser, which is what the translucent theme is for.
                    is VaultState.Locked -> Surface {
                        UnlockScreen(
                            viewModel = viewModel,
                            onUnlocked = {},
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            subtitle = "Save a login for $host",
                            compact = true,
                        )
                    }

                    is VaultState.Unlocked -> SaveDialog(
                        host = host,
                        username = username,
                        password = password,
                        existing = Matching.entriesOnHost(host, current.entries),
                        onCancel = { finish() },
                        onSave = { entry, replacing ->
                            val done = {
                                setResult(Activity.RESULT_OK)
                                finish()
                            }
                            if (replacing == null) {
                                viewModel.add(entry, done)
                            } else {
                                viewModel.update(replacing, entry, done)
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_HOST = "nu.staldal.mypass.autofill.HOST"
        const val EXTRA_USERNAME = "nu.staldal.mypass.autofill.USERNAME"
        const val EXTRA_PASSWORD = "nu.staldal.mypass.autofill.PASSWORD"
    }
}

@Composable
private fun SaveDialog(
    host: String,
    username: String,
    password: String,
    existing: List<PasswordEntry>,
    onCancel: () -> Unit,
    onSave: (entry: PasswordEntry, replacing: String?) -> Unit,
) {
    // An entry for this site with this username is the one the user almost
    // certainly means to update; anything else is a new entry.
    val match = remember(existing, username) {
        existing.firstOrNull { it.username == username }
    }
    var name by remember { mutableStateOf(match?.name ?: host) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (match == null) "Save login in MyPass?" else "Update login in MyPass?") },
        text = {
            Column {
                Text(
                    if (match == null) {
                        "A new entry for ${Validation.displayText(host)}, fillable there from now on."
                    } else {
                        "Replace the password of “${Validation.displayText(match.name)}” for ${Validation.displayText(host)}."
                    }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = if (match == null) name else Validation.displayText(name),
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = match == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (username.isEmpty()) {
                        "No username was filled in."
                    } else {
                        "Username: ${Validation.displayText(username)}"
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        PasswordEntry(
                            name = match?.name ?: name.trim(),
                            username = username,
                            password = Secret(password),
                            // Keep whatever site and realm the existing entry
                            // declared; only its password is being replaced.
                            url = match?.url ?: host,
                            realm = match?.realm,
                        ),
                        match?.name,
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text(if (match == null) "Save" else "Update") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Not now") } },
    )
}
