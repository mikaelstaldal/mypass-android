package nu.staldal.mypass.integration

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import nu.staldal.mypass.MyPassApplication
import nu.staldal.mypass.data.VaultState
import nu.staldal.mypass.data.Validation
import nu.staldal.mypass.ui.VaultViewModel
import nu.staldal.mypass.ui.screens.UnlockScreen
import nu.staldal.mypass.ui.theme.MyPassTheme
import nu.staldal.mypass.vault.PasswordEntry

/**
 * Signature-protected integration endpoint. The caller supplies either an
 * entry name or an HTTPS URL (optionally with an HTTP realm), and receives a
 * unique match immediately after any necessary unlock. Ambiguous matches are
 * resolved by a user-visible chooser.
 */
@OptIn(ExperimentalMaterial3Api::class)
class FetchPasswordActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setResult(Activity.RESULT_CANCELED)
        val query = PasswordRequests.parse(
            intent.getStringExtra(EXTRA_NAME),
            intent.getStringExtra(EXTRA_URL),
            intent.getStringExtra(EXTRA_REALM),
        )
        val caller = callingPackage
        if (intent.action != ACTION_FETCH_PASSWORD || query == null || caller == null) {
            finish()
            return
        }
        val requestDescription = requestDescription(query, caller)

        setContent {
            MyPassTheme {
                Surface { Content(query, requestDescription, onSelect = { returnEntry(query, it) }) }
            }
        }
    }

    @Composable
    private fun Content(
        query: PasswordQuery,
        requestDescription: String,
        onSelect: (PasswordEntry) -> Unit,
    ) {
        val viewModel: VaultViewModel = viewModel()
        val state by viewModel.state.collectAsState()
        val message by viewModel.message.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                viewModel.messageShown()
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (val current = state) {
                VaultState.Locked -> UnlockScreen(
                    viewModel = viewModel,
                    allowRecovery = false,
                    onUnlocked = {},
                    subtitle = requestDescription,
                )
                is VaultState.Unlocked -> {
                    val resolution = remember(query, current.entries) {
                        PasswordRequests.resolve(query, current.entries)
                    }
                    when (resolution) {
                        PasswordResolution.None -> NoMatch(requestDescription)
                        is PasswordResolution.Unique ->
                            LaunchedEffect(resolution.entry) { onSelect(resolution.entry) }
                        is PasswordResolution.Ambiguous ->
                            EntryChooser(requestDescription, resolution.entries, onSelect)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Composable
    private fun NoMatch(requestDescription: String) {
        Scaffold(topBar = { TopAppBar(title = { Text("Choose password") }) }) { padding ->
            Column(modifier = Modifier.padding(padding).padding(24.dp)) {
                Text(requestDescription, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("No matching password entry.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    @Composable
    private fun EntryChooser(
        requestDescription: String,
        entries: List<PasswordEntry>,
        onSelect: (PasswordEntry) -> Unit,
    ) {
        Scaffold(topBar = { TopAppBar(title = { Text("Choose password") }) }) { padding ->
            LazyColumn(modifier = Modifier.padding(padding)) {
                item {
                    Text(
                        requestDescription,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(entries, key = { it.name }) { entry ->
                    ListItem(
                        headlineContent = { Text(Validation.displayText(entry.name)) },
                        supportingContent = {
                            Column {
                                Text(Validation.displayText(entry.username))
                                entry.url?.let { Text(Validation.displayText(it)) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(entry) },
                    )
                }
            }
        }
    }

    private fun returnEntry(query: PasswordQuery, entry: PasswordEntry) {
        if (isFinishing) return
        // Re-check under the repository's state lock at the release point. A
        // chooser may have sat open until auto-lock, and a lock must never
        // leave its old PasswordEntry capable of escaping through the result.
        val entries = (application as MyPassApplication).repository.entriesOrNull()
        val approved = entries?.let { PasswordRequests.select(query, it) }
            ?.find { it == entry }
            ?: run {
                finish()
                return
            }
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_NAME, approved.name)
                .putExtra(EXTRA_RESULT_USERNAME, approved.username)
                .putExtra(EXTRA_RESULT_PASSWORD, approved.password.expose())
                .putExtra(EXTRA_RESULT_URL, approved.url)
                .putExtra(EXTRA_RESULT_REALM, approved.realm),
        )
        finish()
    }

    private fun requestDescription(query: PasswordQuery, caller: String): String {
        val target = when (query) {
            is PasswordQuery.Name -> "entry ${Validation.displayText(query.name)}"
            is PasswordQuery.Site -> buildString {
                val host = PasswordRequests.siteHost(query)
                append(if (host == null) "an ineligible web address" else "site $host")
                query.realm?.let { append(" (realm ${Validation.displayText(it)})") }
            }
        }
        return "$caller requests a password for $target."
    }

    companion object {
        const val ACTION_FETCH_PASSWORD = "nu.staldal.mypass.action.FETCH_PASSWORD"
        const val EXTRA_NAME = "nu.staldal.mypass.extra.NAME"
        const val EXTRA_URL = "nu.staldal.mypass.extra.URL"
        const val EXTRA_REALM = "nu.staldal.mypass.extra.REALM"
        const val EXTRA_RESULT_NAME = "nu.staldal.mypass.extra.RESULT_NAME"
        const val EXTRA_RESULT_USERNAME = "nu.staldal.mypass.extra.RESULT_USERNAME"
        const val EXTRA_RESULT_PASSWORD = "nu.staldal.mypass.extra.RESULT_PASSWORD"
        const val EXTRA_RESULT_URL = "nu.staldal.mypass.extra.RESULT_URL"
        const val EXTRA_RESULT_REALM = "nu.staldal.mypass.extra.RESULT_REALM"
    }
}
