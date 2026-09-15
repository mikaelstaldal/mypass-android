package nu.staldal.pw.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import nu.staldal.pw.ui.VaultViewModel
import nu.staldal.pw.ui.components.SecretField
import nu.staldal.pw.util.Biometrics

/**
 * The way in: unlock an existing vault, or make one.
 *
 * This is also what the autofill authentication activity shows, so it takes
 * everything it needs as parameters rather than reaching for a navigation
 * controller.
 */
@Composable
fun UnlockScreen(
    viewModel: VaultViewModel,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    compact: Boolean = false,
    allowRecovery: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val busy by viewModel.busy.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    // Whether a vault file exists decides the whole screen, and it changes
    // only when this screen creates or imports one.
    var hasVault by remember { mutableStateOf(viewModel.vaultExists()) }
    var confirmReplacement by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importUri = uri
            importing = true
            passphrase = ""
        }
    }

    // Ciphertext export needs no unlock, including when legacy metadata is rejected.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) viewModel.show("Could not write to the file you picked.")
            else viewModel.exportVault(out) { viewModel.show("Encrypted vault exported.") }
        }
    }

    if (confirmReplacement && allowRecovery) {
        AlertDialog(
            onDismissRequest = { confirmReplacement = false },
            title = { Text("Replace the local vault and backup?") },
            text = { Text("The incoming vault will replace all local entries and the backup. Export the encrypted vault first to keep a recoverable copy.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplacement = false
                    importLauncher.launch(arrayOf("*/*"))
                }) { Text("Choose replacement vault") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplacement = false }) { Text("Cancel") }
            },
        )
    }

    // Offer the fingerprint straight away when it is set up: it is the whole
    // point of having enabled it.
    val biometricsUsable = remember(hasVault) {
        hasVault && viewModel.biometrics.isEnabled() && Biometrics.isAvailable(context)
    }
    var biometricsTried by remember { mutableStateOf(false) }

    fun unlockWithBiometrics() {
        val fragmentActivity = activity ?: return
        val cipher = viewModel.biometrics.decryptCipher()
        if (cipher == null) {
            viewModel.show("Fingerprint unlock is no longer available; use the passphrase.")
            return
        }
        Biometrics.authenticate(
            activity = fragmentActivity,
            title = "Unlock pw",
            subtitle = subtitle ?: "Open your password vault",
            cipher = cipher,
            onSuccess = { authenticated ->
                val stored = viewModel.biometrics.retrieve(authenticated)
                if (stored == null) {
                    viewModel.show("Could not read the stored passphrase; use the passphrase.")
                } else {
                    viewModel.unlock(stored, onUnlocked)
                }
            },
            onFailure = { message -> message?.let(viewModel::show) },
        )
    }

    LaunchedEffect(biometricsUsable) {
        if (biometricsUsable && !biometricsTried) {
            biometricsTried = true
            unlockWithBiometrics()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                importing -> "Import a vault"
                hasVault -> "Unlock pw"
                else -> "Create a vault"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(if (compact) 16.dp else 32.dp))

        if (!hasVault && !importing) {
            Text(
                "Choose a master passphrase. It is the only thing protecting the " +
                    "vault, and it cannot be recovered or reset.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
        }
        if (importing) {
            Text(
                "Enter the master passphrase of the vault you picked. It is " +
                    "checked before anything is written.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
        }

        SecretField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = "Master passphrase",
            imeAction = if (hasVault || importing) ImeAction.Done else ImeAction.Next,
        )

        if (!hasVault && !importing) {
            Spacer(Modifier.height(12.dp))
            SecretField(
                value = confirmation,
                onValueChange = { confirmation = it },
                label = "Repeat passphrase",
                isError = confirmation.isNotEmpty() && confirmation != passphrase,
                supportingText = if (confirmation.isNotEmpty() && confirmation != passphrase) {
                    "The two passphrases differ"
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                "Deriving the key — scrypt is deliberately slow.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Button(
                onClick = {
                    when {
                        importing -> {
                            val uri = importUri
                            viewModel.importVault(
                                { uri?.let { context.contentResolver.openInputStream(it) } }, passphrase,
                            ) {
                                hasVault = true
                                importing = false
                                onUnlocked()
                            }
                        }
                        hasVault -> viewModel.unlock(passphrase, onUnlocked)
                        else -> viewModel.createVault(passphrase) {
                            hasVault = true
                            onUnlocked()
                        }
                    }
                },
                enabled = passphrase.isNotEmpty() &&
                    (hasVault || importing || confirmation == passphrase),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        importing -> "Import vault"
                        hasVault -> "Unlock"
                        else -> "Create vault"
                    }
                )
            }

            if (biometricsUsable) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { unlockWithBiometrics() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Unlock with fingerprint")
                }
            }

            if (hasVault && !importing && allowRecovery) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { exportLauncher.launch("pw.scrypt") }) {
                    Text("Export encrypted vault")
                }
                TextButton(onClick = { confirmReplacement = true }) {
                    Text("Import a replacement vault")
                }
            }
            if (hasVault && importing) {
                Text("Import replaces the local entries. Export the encrypted vault first if you need to keep them.")
                TextButton(onClick = { importing = false; importUri = null }) {
                    Text("Cancel import")
                }
            }

            if (!hasVault) {
                Spacer(Modifier.height(8.dp))
                if (importing) {
                    TextButton(onClick = { importing = false; importUri = null }) {
                        Text("Create a new vault instead")
                    }
                } else {
                    TextButton(
                        onClick = {
                            // No MIME type is registered for .scrypt files, so
                            // accept anything and let the decode decide.
                            importLauncher.launch(arrayOf("*/*"))
                        }
                    ) {
                        Text("Import an existing pw.scrypt vault")
                    }
                }
            }
        }
    }
}
