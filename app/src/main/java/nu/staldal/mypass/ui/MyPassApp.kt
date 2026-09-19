package nu.staldal.mypass.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import nu.staldal.mypass.data.VaultState
import nu.staldal.mypass.ui.screens.EntryDetailScreen
import nu.staldal.mypass.ui.screens.EntryFormScreen
import nu.staldal.mypass.ui.screens.EntryListScreen
import nu.staldal.mypass.ui.screens.GenerateScreen
import nu.staldal.mypass.ui.screens.SettingsScreen
import nu.staldal.mypass.ui.screens.UnlockScreen

private object Routes {
    const val UNLOCK = "unlock"
    const val LIST = "list"
    const val DETAIL = "detail/{name}"
    const val ADD = "add"
    const val EDIT = "edit/{name}"
    const val GENERATE = "generate"
    const val SETTINGS = "settings"

    fun detail(name: String) = "detail/${Uri.encode(name)}"
    fun edit(name: String) = "edit/${Uri.encode(name)}"
}

/**
 * The whole app: the unlock screen until the vault is open, the entry screens
 * while it is, and back to unlock the moment it locks — from the Lock button,
 * from the auto-lock timer, or from the process being restored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPassApp() {
    val vaultViewModel: VaultViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val navController = rememberNavController()
    val vaultState by vaultViewModel.state.collectAsState()
    val settings by settingsViewModel.state.collectAsState()
    val message by vaultViewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vaultViewModel.messageShown()
        }
    }

    // One place decides which half of the app is on screen, so a lock from
    // anywhere — including the timer, which no screen knows about — leaves the
    // entry screens at once.
    LaunchedEffect(vaultState) {
        val current = navController.currentDestination?.route
        if (vaultState is VaultState.Locked && current != null && current != Routes.UNLOCK) {
            navController.navigate(Routes.UNLOCK) {
                // The unlock screen is the start destination, so popping to it
                // inclusively clears everything that was shown behind it.
                popUpTo(Routes.UNLOCK) { inclusive = true }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.UNLOCK,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.UNLOCK) {
                UnlockScreen(
                    viewModel = vaultViewModel,
                    allowRecovery = true,
                    onUnlocked = {
                        navController.navigate(Routes.LIST) {
                            popUpTo(Routes.UNLOCK) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.LIST) {
                val entries = (vaultState as? VaultState.Unlocked)?.entries ?: emptyList()
                EntryListScreen(
                    entries = entries,
                    onOpen = { navController.navigate(Routes.detail(it.name)) },
                    onAdd = { navController.navigate(Routes.ADD) },
                    onLock = { vaultViewModel.lock() },
                    onGenerate = { navController.navigate(Routes.GENERATE) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.DETAIL) { backStackEntry ->
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                val entry = vaultViewModel.entry(name)
                if (entry == null) {
                    LaunchedEffect(name) { navController.leaveWhenCurrent(backStackEntry) }
                } else {
                    EntryDetailScreen(
                        entry = entry,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(Routes.edit(name)) },
                        onDelete = {
                            vaultViewModel.remove(name) {
                                vaultViewModel.show("Entry removed")
                                navController.leaveIfCurrent(backStackEntry)
                            }
                        },
                        onCopy = vaultViewModel::copyToClipboard,
                    )
                }
            }

            composable(Routes.ADD) { backStackEntry ->
                EntryFormScreen(
                    existing = null,
                    defaultLength = settings.passwordLength,
                    defaultCharset = settings.passwordCharset,
                    onBack = { navController.popBackStack() },
                    onSave = { entry ->
                        vaultViewModel.add(entry) {
                            vaultViewModel.show("Entry added")
                            navController.leaveIfCurrent(backStackEntry)
                        }
                    },
                    onGenerate = vaultViewModel::generatePassword,
                )
            }

            composable(Routes.EDIT) { backStackEntry ->
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                val entry = vaultViewModel.entry(name)
                if (entry == null) {
                    LaunchedEffect(name) { navController.leaveWhenCurrent(backStackEntry) }
                } else {
                    EntryFormScreen(
                        existing = entry,
                        defaultLength = settings.passwordLength,
                        defaultCharset = settings.passwordCharset,
                        onBack = { navController.popBackStack() },
                        onSave = { updated ->
                            vaultViewModel.update(name, updated) {
                                vaultViewModel.show("Entry updated")
                                // A rename makes the detail route behind this
                                // one stale, so go back to the list instead —
                                // and that route then pops itself, above.
                                navController.leaveIfCurrent(backStackEntry, Routes.LIST)
                            }
                        },
                        onGenerate = vaultViewModel::generatePassword,
                    )
                }
            }

            composable(Routes.GENERATE) {
                GenerateScreen(
                    defaultLength = settings.passwordLength,
                    defaultCharset = settings.passwordCharset,
                    onBack = { navController.popBackStack() },
                    onGenerate = vaultViewModel::generatePassword,
                    onCopy = vaultViewModel::copyToClipboard,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    vaultViewModel = vaultViewModel,
                    onBack = { navController.popBackStack() },
                    onAutoLockMinutes = settingsViewModel::setAutoLockMinutes,
                    onLockOnBackground = settingsViewModel::setLockOnBackground,
                    onClipboardClearSeconds = settingsViewModel::setClipboardClearSeconds,
                    onPasswordLength = settingsViewModel::setPasswordLength,
                    onPasswordCharset = settingsViewModel::setPasswordCharset,
                    onScryptLogN = settingsViewModel::setScryptLogN,
                    onBrowserCertificatePins = settingsViewModel::setBrowserCertificatePins,
                    onAutofillDiagnostics = settingsViewModel::setAutofillDiagnostics,
                )
            }
        }
    }
}

/**
 * Pop [entry] off the back stack once it is the destination on top of it. A
 * screen whose subject has vanished under it — the entry deleted, renamed, or
 * gone because the vault locked — leaves; but whatever made it vanish has often
 * navigated away already, and the screen is only still composed for its exit
 * transition. Popping there would take the destination below it too, and two
 * pops for one delete empty the back stack and leave a blank screen.
 *
 * Waiting rather than testing once: the flow reports the current destination as
 * soon as it is collected, so a screen that is on top leaves immediately, while
 * one that is covered — a stale detail screen under the edit screen that renamed
 * its entry — waits until it is uncovered instead of being stranded by a single
 * check made at the wrong moment. If it never comes back, leaving composition
 * cancels the wait with it.
 */
private suspend fun NavHostController.leaveWhenCurrent(entry: NavBackStackEntry) {
    currentBackStackEntryFlow.first { it.id == entry.id }
    popBackStack()
}

/**
 * Pop [entry], up to [upTo] if given, but only while [entry] is still the
 * destination on top of the back stack. A vault write takes the scrypt KDF's
 * several seconds, and nothing stops the user leaving the screen that started
 * one: by the time it reports success, the screen that asked for it may be gone
 * and another one — or several — standing where it was. An unguarded pop would
 * take those instead.
 */
private fun NavHostController.leaveIfCurrent(entry: NavBackStackEntry, upTo: String? = null) {
    if (currentBackStackEntry?.id != entry.id) return
    if (upTo == null) popBackStack() else popBackStack(upTo, inclusive = false)
}
