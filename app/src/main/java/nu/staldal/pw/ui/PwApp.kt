package nu.staldal.pw.ui

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nu.staldal.pw.data.VaultState
import nu.staldal.pw.ui.screens.EntryDetailScreen
import nu.staldal.pw.ui.screens.EntryFormScreen
import nu.staldal.pw.ui.screens.EntryListScreen
import nu.staldal.pw.ui.screens.GenerateScreen
import nu.staldal.pw.ui.screens.SettingsScreen
import nu.staldal.pw.ui.screens.UnlockScreen

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
fun PwApp() {
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
                    LaunchedEffect(name) { navController.popBackStack() }
                } else {
                    EntryDetailScreen(
                        entry = entry,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(Routes.edit(name)) },
                        onDelete = {
                            vaultViewModel.remove(name) {
                                vaultViewModel.show("Entry removed")
                                navController.popBackStack()
                            }
                        },
                        onCopy = vaultViewModel::copyToClipboard,
                    )
                }
            }

            composable(Routes.ADD) {
                EntryFormScreen(
                    existing = null,
                    defaultLength = settings.passwordLength,
                    defaultCharset = settings.passwordCharset,
                    onBack = { navController.popBackStack() },
                    onSave = { entry ->
                        vaultViewModel.add(entry) {
                            vaultViewModel.show("Entry added")
                            navController.popBackStack()
                        }
                    },
                    onGenerate = vaultViewModel::generatePassword,
                )
            }

            composable(Routes.EDIT) { backStackEntry ->
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                val entry = vaultViewModel.entry(name)
                if (entry == null) {
                    LaunchedEffect(name) { navController.popBackStack() }
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
                                // one stale, so go back to the list instead.
                                navController.popBackStack(Routes.LIST, inclusive = false)
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
                )
            }
        }
    }
}
