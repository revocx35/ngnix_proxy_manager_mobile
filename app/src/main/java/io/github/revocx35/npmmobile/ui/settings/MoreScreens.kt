package io.github.revocx35.npmmobile.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.BuildConfig
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.core.Setting
import io.github.revocx35.npmmobile.core.SettingRequest
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.data.ThemeMode
import io.github.revocx35.npmmobile.ui.AboutRoute
import io.github.revocx35.npmmobile.ui.AccessListsRoute
import io.github.revocx35.npmmobile.ui.AppearanceRoute
import io.github.revocx35.npmmobile.ui.AuditLogRoute
import io.github.revocx35.npmmobile.ui.DefaultSiteRoute
import io.github.revocx35.npmmobile.ui.DiagnosticsRoute
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.ProfileRoute
import io.github.revocx35.npmmobile.ui.ServersRoute
import io.github.revocx35.npmmobile.ui.UiError
import io.github.revocx35.npmmobile.ui.UsersRoute
import io.github.revocx35.npmmobile.ui.components.AppTopBar
import io.github.revocx35.npmmobile.ui.components.ConfirmDialog
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.FormCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.InitialsAvatar
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.NavRow
import io.github.revocx35.npmmobile.ui.components.PrimaryButton
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.SwitchRow
import io.github.revocx35.npmmobile.ui.components.Tag
import io.github.revocx35.npmmobile.ui.copyToClipboard
import io.github.revocx35.npmmobile.ui.openUrl
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val REPO_URL = "https://github.com/revocx35/ngnix_proxy_manager_mobile"

@Composable
fun MoreScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val session = LocalSession.current
    val user = session.user
    var confirmSignOut by remember { mutableStateOf(false) }
    Scaffold(topBar = { AppTopBar("More") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Card(
                Modifier.fillMaxWidth().padding(16.dp).clickable { nav.navigate(ProfileRoute) }.testTag("profileCard"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(user?.displayName ?: session.account.label, size = 48)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user?.displayName ?: session.account.label, style = MaterialTheme.typography.titleMedium)
                        Text(user?.email ?: session.account.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(session.account.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(AppIcons.ChevronRight, null)
                }
            }
            SectionHeader("Server")
            if (user?.canView(Section.AccessLists) != false) NavRow("Access lists", AppIcons.AccessList, { nav.navigate(AccessListsRoute) }, "Basic auth and IP rules for proxy hosts")
            if (user?.isAdmin == true) {
                NavRow("Users", AppIcons.Users, { nav.navigate(UsersRoute) }, "Accounts and permissions")
                NavRow("Audit log", AppIcons.History, { nav.navigate(AuditLogRoute) }, "Who changed what")
                NavRow("Default site", AppIcons.Web, { nav.navigate(DefaultSiteRoute) }, "What unknown hostnames see")
            }
            SectionHeader("App")
            NavRow("Servers & accounts", AppIcons.Server, { nav.navigate(ServersRoute) }, "Switch between NPM instances")
            NavRow("Appearance", AppIcons.Palette, { nav.navigate(AppearanceRoute) })
            NavRow("Crash reports", AppIcons.Bug, { nav.navigate(DiagnosticsRoute) })
            NavRow("About", AppIcons.Info, { nav.navigate(AboutRoute) }, "Version ${BuildConfig.VERSION_NAME}")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavRow("Sign out", AppIcons.Logout, { confirmSignOut = true }, trailing = {})
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmSignOut) {
        ConfirmDialog("Sign out?", "You'll need your password to sign in to ${session.account.host} again.", "Sign out", { graph.session.signOut() }, { confirmSignOut = false })
    }
}

// ---------------------------------------------------------------------------------------------- Appearance

@Composable
fun AppearanceScreen(nav: NavHostController) {
    val prefs = LocalGraph.current.prefs
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val dynamic by prefs.dynamicColor.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar("Appearance", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormCard {
                listOf(ThemeMode.System to "System default", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark").forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(theme == mode, role = Role.RadioButton) { prefs.setTheme(mode) }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == mode, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                FormCard { SwitchRow("Dynamic colors", dynamic, { prefs.setDynamicColor(it) }, "Use colors from your wallpaper") }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------- Servers

@Composable
fun ServersScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val session = LocalSession.current
    val accounts by graph.session.accounts.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<io.github.revocx35.npmmobile.data.Account?>(null) }
    Scaffold(topBar = { AppTopBar("Servers & accounts", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            accounts.forEach { acc ->
                val active = acc.id == session.account.id
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !active) { graph.session.switchTo(acc.id) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Server, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(acc.host, style = MaterialTheme.typography.bodyLarge)
                        Text(acc.email + (acc.npmVersion?.let { " · NPM $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active) Tag("Active", icon = AppIcons.Check)
                    IconButton(onClick = { remove = acc }) { Icon(AppIcons.Delete, "Remove ${acc.host}") }
                }
            }
            Spacer(Modifier.height(8.dp))
            NavRow("Add a server", AppIcons.Add, { graph.session.addAnother() }, trailing = {})
            Text(
                "Each saved server keeps its own session. Tokens are encrypted with a key from the Android Keystore that never leaves this device.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    remove?.let { acc ->
        ConfirmDialog("Remove ${acc.host}?", "The saved sign-in for ${acc.email} will be deleted from this device. Nothing changes on the server.", "Remove", { graph.session.removeAccount(acc.id) }, { remove = null }, destructive = true)
    }
}

// ------------------------------------------------------------------------------------------ Default site

class DefaultSiteViewModel(private val graph: AppGraph) : ViewModel() {
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<UiError?>(null)
        private set
    var value by mutableStateOf("congratulations")
    var redirect by mutableStateOf("")
    var html by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    var saved by mutableStateOf(false)

    init { load() }

    fun load() {
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                val s = graph.session.call { it.settings() }.firstOrNull { it.id == Setting.DEFAULT_SITE } ?: error("default-site setting missing")
                value = s.value
                redirect = s.metaString("redirect")
                html = s.metaString("html")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadError = e.toUiError()
            } finally {
                loading = false
            }
        }
    }

    val redirectError get() = if (value == "redirect" && !(redirect.startsWith("http://") || redirect.startsWith("https://"))) "Enter a full URL (https://…)" else null

    fun save() {
        if (redirectError != null || busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                graph.session.call { it.updateSetting(Setting.DEFAULT_SITE, SettingRequest.defaultSite(value, redirect.trim(), html)) }
                saved = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun DefaultSiteScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: DefaultSiteViewModel = viewModel { DefaultSiteViewModel(graph) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.saved) { if (vm.saved) { snackbar.showSnackbar("Default site saved"); vm.saved = false } }
    Scaffold(topBar = { AppTopBar("Default site", onBack = { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        when {
            vm.loading -> LoadingBox(Modifier.padding(padding))
            vm.loadError != null -> Column(Modifier.padding(padding).padding(16.dp)) { ErrorCard(vm.loadError!!.message, onRetry = { vm.load() }) }
            else -> Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What NPM shows when a request arrives for a hostname it doesn't know.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.error?.let { ErrorCard(it.message) }
                FormCard {
                    listOf(
                        "congratulations" to ("Congratulations page" to "NPM's default welcome page"),
                        "404" to ("404 page" to "A plain \"Not Found\" page"),
                        "444" to ("No response" to "Close the connection (nginx 444)"),
                        "redirect" to ("Redirect" to "Send visitors to another address"),
                        "html" to ("Custom page" to "Serve your own HTML"),
                    ).forEach { (v, labels) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(vm.value == v, role = Role.RadioButton) { vm.value = v }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vm.value == v, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(labels.first)
                                Text(labels.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (vm.value == "redirect") FormTextField(vm.redirect, { vm.redirect = it }, "Redirect to", error = vm.redirectError, placeholder = "https://example.com")
                if (vm.value == "html") FormTextField(vm.html, { vm.html = it }, "HTML", singleLine = false, minLines = 8, mono = true)
                PrimaryButton("Save", { vm.save() }, busy = vm.busy)
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- Diagnostics

@Composable
fun DiagnosticsScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    var text by remember { mutableStateOf(graph.diagnostics.all()) }
    LaunchedEffect(Unit) { graph.diagnostics.markSeen() }
    Scaffold(topBar = {
        AppTopBar("Crash reports", onBack = { nav.popBackStack() }, actions = {
            if (text != null) {
                IconButton(onClick = { context.copyToClipboard("Crash report", text!!) }) { Icon(AppIcons.Copy, "Copy") }
                IconButton(onClick = { graph.diagnostics.clear(); text = null }) { Icon(AppIcons.Delete, "Clear") }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoBanner("Reports stay on this device. They contain only technical details (no server data, passwords or tokens). Copy one into a GitHub issue if something went wrong.")
            if (text == null) {
                Text("No crash reports. 🎉", style = MaterialTheme.typography.bodyLarge)
            } else {
                SelectionContainer { Text(text!!, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { context.openUrl("$REPO_URL/issues/new") }) { Text("Open a GitHub issue") }
        }
    }
}

// ---------------------------------------------------------------------------------------------- About

@Composable
fun AboutScreen(nav: NavHostController) {
    val context = LocalContext.current
    val session = LocalSession.current
    Scaffold(topBar = { AppTopBar("About", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NPM Mobile", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "A native Android client for Nginx Proxy Manager. It talks directly to your NPM server's API; there is no cloud service in between and no analytics.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "This is an unofficial app, not affiliated with the Nginx Proxy Manager project.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FormCard {
                NavRow("Source code", AppIcons.Code, { context.openUrl(REPO_URL) }, "github.com/revocx35/ngnix_proxy_manager_mobile", trailing = { Icon(AppIcons.OpenInNew, null) })
                NavRow("Nginx Proxy Manager", AppIcons.Language, { context.openUrl("https://nginxproxymanager.com") }, "nginxproxymanager.com", trailing = { Icon(AppIcons.OpenInNew, null) })
                NavRow("Open the web UI", AppIcons.Public, { context.openUrl(session.account.server) }, session.account.server, trailing = { Icon(AppIcons.OpenInNew, null) })
            }
            Text("Icons: Material Symbols (Apache 2.0). License: MIT.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
