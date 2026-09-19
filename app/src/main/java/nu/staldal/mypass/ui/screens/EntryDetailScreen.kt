package nu.staldal.mypass.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nu.staldal.mypass.data.Validation
import nu.staldal.mypass.vault.PasswordEntry

/**
 * One entry. The password is masked until asked for, and copying it is the
 * ordinary way to use it — the clipboard is cleared again after the timeout
 * in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    entry: PasswordEntry,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (label: String, value: String, confirmation: String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(Validation.displayText(entry.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit entry")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
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
            if (entry.username.isNotEmpty()) {
                Field("Username", Validation.displayText(entry.username))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onCopy("MyPass username", entry.username, "Username copied")
                    }
                ) {
                    Text("Copy username")
                }
                Spacer(Modifier.height(24.dp))
            }

            Field("Password", if (revealed) entry.password.expose() else "•".repeat(12))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onCopy("MyPass password", entry.password.expose(), "Password copied")
                    }
                ) {
                    Text("Copy password")
                }
                OutlinedButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "Hide" else "Show")
                }
            }

            if (entry.url != null) {
                Spacer(Modifier.height(24.dp))
                Field("Site (url)", Validation.displayText(entry.url))
                Text(
                    "This entry can be filled in a browser on this site.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Field("Site (url)", "— not set —")
                Text(
                    "Without a url this entry is never offered in a browser.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (entry.realm != null) {
                Spacer(Modifier.height(24.dp))
                Field("HTTP realm", Validation.displayText(entry.realm))
                Text(
                    "Used by desktop MyPass's browser integration; Android's " +
                        "autofill never sees an HTTP authentication challenge.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove “${Validation.displayText(entry.name)}”?") },
            text = { Text("The password is deleted from the vault. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
