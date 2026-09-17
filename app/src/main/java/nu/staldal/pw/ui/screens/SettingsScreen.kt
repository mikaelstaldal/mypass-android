package nu.staldal.pw.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import nu.staldal.pw.autofill.BrowserCertificates
import nu.staldal.pw.autofill.FillDiagnostics
import nu.staldal.pw.autofill.Browsers
import nu.staldal.pw.data.ScryptDefaults
import nu.staldal.pw.data.SettingsState
import nu.staldal.pw.ui.VaultViewModel
import nu.staldal.pw.ui.components.SecretField
import nu.staldal.pw.util.Biometrics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsState,
    vaultViewModel: VaultViewModel,
    onBack: () -> Unit,
    onAutoLockMinutes: (Int) -> Unit,
    onLockOnBackground: (Boolean) -> Unit,
    onClipboardClearSeconds: (Int) -> Unit,
    onPasswordLength: (Int) -> Unit,
    onPasswordCharset: (String) -> Unit,
    onScryptLogN: (Int) -> Unit,
    onBrowserCertificatePins: (String) -> Unit,
    onAutofillDiagnostics: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var changePassphrase by remember { mutableStateOf(false) }
    var biometricsEnabled by remember { mutableStateOf(vaultViewModel.biometrics.isEnabled()) }
    val busy by vaultViewModel.busy.collectAsState()
    LaunchedEffect(busy) {
        if (!busy) biometricsEnabled = vaultViewModel.biometrics.isEnabled()
    }
    var enrollPassphrase by remember { mutableStateOf<String?>(null) }

    val autofillManager = remember { context.getSystemService(AutofillManager::class.java) }
    // Recomputed whenever the screen is recomposed after returning from the
    // system settings screen.
    var autofillEnabled by remember {
        mutableStateOf(autofillManager?.hasEnabledAutofillServices() == true)
    }
    val autofillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        autofillEnabled = autofillManager?.hasEnabledAutofillServices() == true
    }

    val exportVaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                vaultViewModel.show("Could not write to the file you picked.")
            } else {
                vaultViewModel.exportVault(out) {
                    vaultViewModel.show("Encrypted vault exported.")
                }
            }
        }
    }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                vaultViewModel.show("Could not write to the file you picked.")
            } else {
                vaultViewModel.exportJson(out) {
                    vaultViewModel.show("Decrypted vault exported — keep it somewhere safe.")
                }
            }
        }
    }
    var confirmJsonExport by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var keepassUri by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        // No MIME type is registered for .scrypt files, so accept anything and
        // let the decode decide.
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importUri = uri }
    val keepassLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) keepassUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SectionHeader("Locking")
            NumberSetting(
                label = "Auto-lock after (minutes)",
                help = "Counted from the last time the vault was read or written. " +
                    "0 keeps it open until you lock it or the app is killed.",
                value = settings.autoLockMinutes,
                onValue = onAutoLockMinutes,
            )
            SwitchSetting(
                label = "Lock when pw leaves the screen",
                help = "Stricter, but the autofill service shares this process: " +
                    "with this on, every fill in a browser asks for the passphrase again.",
                checked = settings.lockOnBackground,
                onChecked = onLockOnBackground,
            )

            Spacer(Modifier.height(8.dp))
            SwitchSetting(
                label = "Unlock with fingerprint",
                help = if (Biometrics.isAvailable(context)) {
                    "Wraps the master passphrase with a key in the device keystore " +
                        "that needs a fingerprint to use. Enrolling a new fingerprint " +
                        "destroys it."
                } else {
                    "No strong biometric is set up on this device."
                },
                checked = biometricsEnabled,
                enabled = Biometrics.isAvailable(context),
                onChecked = { wanted ->
                    if (!wanted) {
                        vaultViewModel.biometrics.clear()
                        biometricsEnabled = false
                    } else {
                        enrollPassphrase = ""
                    }
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionHeader("Clipboard")
            NumberSetting(
                label = "Clear a copied password after (seconds)",
                help = "0 leaves the clipboard untouched. A clipboard history " +
                    "manager may keep a copy pw cannot reach.",
                value = settings.clipboardClearSeconds,
                onValue = onClipboardClearSeconds,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionHeader("Generated passwords")
            NumberSetting(
                label = "Default length",
                help = null,
                value = settings.passwordLength,
                onValue = onPasswordLength,
            )
            TextSetting(
                label = "Default characters",
                help = null,
                value = settings.passwordCharset,
                onValue = onPasswordCharset,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionHeader("Browser integration")
            Text(
                if (autofillEnabled) {
                    "pw is the autofill service. It fills login forms in a browser " +
                        "only, on https: sites, and only from entries that have a url."
                } else {
                    "pw is not the autofill service. Turn it on to fill login forms " +
                        "in your browser."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                        "package:${context.packageName}".toUri(),
                    )
                    autofillLauncher.launch(intent)
                }
            ) {
                Text(if (autofillEnabled) "Autofill settings" else "Use pw for autofill")
            }
            Spacer(Modifier.height(12.dp))
            BrowserEnrollment(settings.browserCertificatePins, onBrowserCertificatePins)
            Spacer(Modifier.height(12.dp))
            FillDiagnosticsSetting(settings.autofillDiagnostics, onAutofillDiagnostics)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionHeader("Vault file")
            NumberSetting(
                label = "scrypt cost, log2(N)",
                help = "This device supports ${ScryptDefaults.MIN_LOG_N}–${ScryptDefaults.MAX_LOG_N}. " +
                    "Desktop cost 17 needs about 128 MiB. The default is lowered " +
                    "to fit this phone; lower costs weaken offline attack resistance. " +
                    "Takes effect on the next write.",
                value = settings.scryptLogN,
                onValue = { onScryptLogN(it.coerceIn(ScryptDefaults.MIN_LOG_N, ScryptDefaults.MAX_LOG_N)) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { changePassphrase = true }, Modifier.fillMaxWidth()) {
                Text("Change master passphrase")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { exportVaultLauncher.launch("pw.scrypt") },
                Modifier.fillMaxWidth(),
            ) {
                Text("Export encrypted vault (pw.scrypt)")
            }
            Text(
                "The file as it is on disk. `scrypt dec pw.scrypt` on a desktop " +
                    "reads it, and so does desktop pw.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmJsonExport = true },
                Modifier.fillMaxWidth(),
            ) {
                Text("Export decrypted JSON")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth(),
            ) {
                Text("Import a vault, replacing this one")
            }
            Text(
                "For re-syncing from a desktop. The file is verified before " +
                    "anything is written. The local backup also becomes the imported vault; " +
                    "previous local entries are discarded. Export them first if needed.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { keepassLauncher.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth(),
            ) {
                Text("Import entries from KeePass (KDBX)")
            }
            Text(
                "Adds the live entries from a KeePass 2.x database. Title, username, " +
                    "password and URL are imported; groups, history, notes and attachments " +
                    "are not. Existing entries are kept. Duplicate or invalid titles cancel " +
                    "the whole import.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (changePassphrase) {
        ChangePassphraseDialog(
            onDismiss = { changePassphrase = false },
            onConfirm = { newPassphrase ->
                changePassphrase = false
                val wasEnabled = vaultViewModel.biometrics.isEnabled()
                vaultViewModel.changePassphrase(newPassphrase) {
                    vaultViewModel.show(
                        "Passphrase changed." +
                            if (wasEnabled) " Fingerprint unlock was turned off." else ""
                    )
                }
            },
        )
    }

    val enrolling = enrollPassphrase
    if (enrolling != null) {
        EnrollBiometricsDialog(
            onDismiss = { enrollPassphrase = null },
            onConfirm = { passphrase ->
                enrollPassphrase = null
                vaultViewModel.verifyBiometricEnrollment(passphrase) { token ->
                    val fragmentActivity = activity
                    val cipher = vaultViewModel.biometrics.encryptCipher()
                    if (fragmentActivity == null || cipher == null) {
                        vaultViewModel.show("This device's keystore is not available.")
                    } else {
                        Biometrics.authenticate(
                            activity = fragmentActivity,
                            title = "Enable fingerprint unlock",
                            subtitle = "Confirm to store your passphrase",
                            cipher = cipher,
                            onSuccess = { authenticated ->
                                vaultViewModel.completeBiometricEnrollment(
                                    token,
                                    { vaultViewModel.biometrics.store(authenticated, passphrase) },
                                ) {
                                    biometricsEnabled = true
                                    vaultViewModel.show("Fingerprint unlock enabled.")
                                }
                            },
                            onFailure = { message -> message?.let(vaultViewModel::show) },
                        )
                    }
                }
            },
        )
    }

    val pendingImport = importUri
    if (pendingImport != null) {
        ImportVaultDialog(
            onDismiss = { importUri = null },
            onConfirm = { importPassphrase ->
                importUri = null
                vaultViewModel.importVault(
                    { context.contentResolver.openInputStream(pendingImport) }, importPassphrase,
                ) {
                    vaultViewModel.show("Vault imported.")
                    onBack()
                }
            },
        )
    }

    val pendingKeePass = keepassUri
    if (pendingKeePass != null) {
        ImportKeePassDialog(
            onDismiss = { keepassUri = null },
            onConfirm = { passphrase ->
                keepassUri = null
                vaultViewModel.importKeePass(
                    { context.contentResolver.openInputStream(pendingKeePass) }, passphrase,
                ) {
                    vaultViewModel.show("KeePass entries imported.")
                }
            },
        )
    }

    if (confirmJsonExport) {
        AlertDialog(
            onDismissRequest = { confirmJsonExport = false },
            title = { Text("Export decrypted JSON?") },
            text = {
                Text(
                    "The file will contain every password in plain text. Anything " +
                        "that can read the place you save it can read them."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmJsonExport = false
                        exportJsonLauncher.launch("pw-export.json")
                    }
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { confirmJsonExport = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChangePassphraseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change master passphrase") },
        text = {
            Column {
                Text(
                    "The vault and local backup are re-encrypted under the new passphrase. " +
                        "Previously exported copies still open with their original passphrase."
                )
                Spacer(Modifier.height(12.dp))
                SecretField(passphrase, { passphrase = it }, "New passphrase")
                Spacer(Modifier.height(8.dp))
                SecretField(
                    confirmation,
                    { confirmation = it },
                    "Repeat passphrase",
                    isError = confirmation.isNotEmpty() && confirmation != passphrase,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty() && passphrase == confirmation,
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportVaultDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import vault") },
        text = {
            Column {
                Text(
                    "Every entry in this device's vault is replaced by the ones " +
                        "in the file. Enter that file's master passphrase, which " +
                        "becomes this device's."
                )
                Spacer(Modifier.height(12.dp))
                SecretField(passphrase, { passphrase = it }, "Master passphrase of the file")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty(),
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeePassDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from KeePass") },
        text = {
            Column {
                Text("Enter the KeePass database's master passphrase.")
                Spacer(Modifier.height(12.dp))
                SecretField(passphrase, { passphrase = it }, "KeePass master passphrase")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EnrollBiometricsDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable fingerprint unlock") },
        text = {
            Column {
                Text(
                    "Type the master passphrase once more. It is wrapped with a " +
                        "keystore key that needs your fingerprint to use."
                )
                Spacer(Modifier.height(12.dp))
                SecretField(passphrase, { passphrase = it }, "Master passphrase")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty(),
            ) { Text("Enable") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NumberSetting(label: String, help: String?, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter(Char::isDigit)
            text.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TextSetting(label: String, help: String?, value: String, onValue: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwitchSetting(
    label: String,
    help: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (help != null) {
                Text(help, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/**
 * The opt-in refusal log. Every refusal in the autofill path is silent — the
 * framework has no way for a service to explain itself — so without this a
 * browser that does not fill looks the same whatever the cause.
 */
@Composable
private fun FillDiagnosticsSetting(enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    SwitchSetting(
        label = "Record why fills were refused",
        help = "Keeps the last ${FillDiagnostics.CAPACITY} autofill requests in memory: the " +
            "browser, the origin it reported, and the decision. No field contents, no entry " +
            "names. Also writes the same metadata to Logcat with tag pw-autofill while enabled; " +
            "forgotten in pw when switched off or when pw stops.",
        checked = enabled,
        onChecked = onEnabled,
    )
    if (!enabled) return
    val records by FillDiagnostics.records.collectAsState()
    Spacer(Modifier.height(8.dp))
    if (records.isEmpty()) {
        Text(
            "No requests recorded yet. Open a login page in your browser and tap the " +
                "password field, then come back.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    records.forEach { record ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(record.outcome.name, style = MaterialTheme.typography.bodyLarge)
            Text(record.outcome.description, style = MaterialTheme.typography.bodySmall)
            Text(
                "from ${record.browserPackage ?: "(no package)"} · " +
                    (if (record.compatibilityMode) "compatibility mode · " else "") +
                    "scheme ${record.scheme ?: "(none)"} · host ${record.host ?: "(none)"} · " +
                    "${record.classifiedFields} classified field(s)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (records.isNotEmpty()) {
        TextButton(onClick = { FillDiagnostics.clear() }) { Text("Clear records") }
    }
}

@Composable
private fun BrowserEnrollment(pins: String, onPins: (String) -> Unit) {
    val context = LocalContext.current
    var packageName by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, Browsers.Identity>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    Text("Other browser distributions need explicit certificate enrollment. " +
        "Previous package-only permissions were removed.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = packageName,
        onValueChange = { packageName = it; error = null; feedback = null },
        label = { Text("Installed browser package") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = {
        error = null
        feedback = null
        val pkg = packageName.trim()
        val identity = if (Browsers.validPackageName(pkg))
            BrowserCertificates.read(context.packageManager, pkg) else null
        if (identity == null) error = "Cannot check this app's certificate. It may be uninstalled or not visible as a web browser. No trust granted."
        else pending = pkg to identity
    }, enabled = packageName.isNotBlank()) { Text("Review enrollment") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    feedback?.let { Text(it) }
    Browsers.parseEnrollments(pins).forEach { (pkg, digests) ->
        Text(pkg)
        Text(digests.sorted().joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onPins(Browsers.remove(pins, pkg)) }) {
            Text("Remove enrollment")
        }
    }
    pending?.let { (pkg, identity) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Trust this browser?") },
            text = { Column {
                Text("$pkg may claim ANY website and receive credentials for it. " +
                    "Only enroll an app whose publisher and installation source you trust. " +
                    "The certificate shown identifies this installed app; it does not prove it is safe.")
                if (pkg in Browsers.KNOWN) Text("This adds an acceptable signer alongside pw's built-in publisher pins.")
                Text("SHA-256 signing certificates:")
                Text(identity.current.sorted().joinToString("\n"))
            } },
            confirmButton = { TextButton(onClick = {
                // Recheck so an uninstall/reinstall during disclosure cannot silently enroll a new key.
                val fresh = BrowserCertificates.read(context.packageManager, pkg)
                if (fresh == identity) {
                    onPins(Browsers.enroll(pins, pkg, identity))
                    error = null
                    feedback = "Browser certificate enrolled."
                }
                else error = "The installed app changed. Review its certificate again."
                pending = null
            }) { Text("Trust this installed app") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
