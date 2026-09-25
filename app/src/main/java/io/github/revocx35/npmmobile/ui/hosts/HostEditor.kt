package io.github.revocx35.npmmobile.ui.hosts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.HostKind
import io.github.revocx35.npmmobile.core.HostStatus
import io.github.revocx35.npmmobile.core.NpmClient
import io.github.revocx35.npmmobile.core.NpmHost
import io.github.revocx35.npmmobile.core.NpmDates
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.ui.Format
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.UiError
import io.github.revocx35.npmmobile.ui.components.BusyDialog
import io.github.revocx35.npmmobile.ui.components.ConfirmDialog
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.StatusDot
import io.github.revocx35.npmmobile.ui.components.label
import io.github.revocx35.npmmobile.ui.openUrl
import io.github.revocx35.npmmobile.ui.siteUrl
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.launch

/**
 * Shared logic of the four host editors: load an existing host (id != 0), validate, save, enable/disable and
 * delete, and detect unsaved changes via [snapshot].
 */
abstract class HostEditorViewModel<H : NpmHost>(protected val graph: AppGraph, val kind: HostKind, val id: Int) : ViewModel() {
    val isNew get() = id == 0
    var loading by mutableStateOf(id != 0)
        private set
    var loadError by mutableStateOf<UiError?>(null)
        private set
    var host by mutableStateOf<H?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf<UiError?>(null)
    var showErrors by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    /** Set when the screen should close (saved or deleted). */
    var finished by mutableStateOf(false)
        private set
    private var initialSnapshot: String? = null

    protected abstract suspend fun fetch(client: NpmClient, id: Int): H
    protected abstract fun fill(host: H)
    protected abstract suspend fun persist(client: NpmClient): H
    /** Index of the first tab with an error, or null if the form is valid. */
    abstract fun firstInvalidTab(): Int?
    abstract fun snapshot(): String
    protected open val requestsCertificate: Boolean get() = false

    /** Subclasses call this at the end of their `init`, once their form fields exist. */
    protected fun start() {
        if (id != 0) load() else initialSnapshot = snapshot()
    }

    fun load() {
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                val h = graph.session.call { fetch(it, id) }
                host = h
                fill(h)
                initialSnapshot = snapshot()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loadError = e.toUiError()
            } finally {
                loading = false
            }
        }
    }

    val dirty: Boolean get() = initialSnapshot != null && initialSnapshot != snapshot()

    /** Returns the tab to show when validation failed, or null when saving started. */
    fun save(): Int? {
        showErrors = true
        firstInvalidTab()?.let { return it }
        if (busy != null) return null
        saveError = null
        busy = if (requestsCertificate) "Requesting a Let's Encrypt certificate… This can take a minute." else "Saving…"
        viewModelScope.launch {
            try {
                val saved = graph.session.call { persist(it) }
                host = saved
                graph.repository.hostsChanged(kind)
                if (requestsCertificate) graph.repository.certificates.refresh()
                finished = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                saveError = e.toUiError()
                // A failed certificate request may still have created the host: reload the list.
                graph.repository.hosts(kind).refresh()
            } finally {
                busy = null
            }
        }
        return null
    }

    fun setEnabled(enabled: Boolean) {
        val h = host ?: return
        busy = if (enabled) "Enabling…" else "Disabling…"
        viewModelScope.launch {
            try {
                graph.session.call { if (enabled) it.enableHost(kind, h.id) else it.disableHost(kind, h.id) }
                val fresh = graph.session.call { fetch(it, id) }
                host = fresh
                message = if (enabled) "Enabled" else "Disabled"
                graph.repository.hostsChanged(kind)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = e.toUiError().message
            } finally {
                busy = null
            }
        }
    }

    fun delete() {
        val h = host ?: return
        busy = "Deleting…"
        viewModelScope.launch {
            try {
                graph.session.call { it.deleteHost(kind, h.id) }
                graph.repository.hostsChanged(kind)
                finished = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = e.toUiError().message
            } finally {
                busy = null
            }
        }
    }

}

/**
 * Scaffold of a host editor: top bar with Save and a menu (open, enable/disable, delete), status header,
 * optional tabs, errors, a busy dialog, and a "discard changes?" check on back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <H : NpmHost> HostEditorScaffold(
    nav: NavHostController,
    vm: HostEditorViewModel<H>,
    tabs: List<String>,
    openDomain: String? = null,
    content: @Composable ColumnScope.(tab: Int) -> Unit,
) {
    val user = LocalSession.current.user
    val canManage = user?.canManage(vm.kind.section) ?: true
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.finished) { if (vm.finished) nav.popBackStack() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    val back = { if (vm.dirty && canManage) confirmDiscard = true else nav.popBackStack() }
    BackHandler(enabled = vm.dirty && canManage) { confirmDiscard = true }

    val title = when {
        vm.isNew -> "New ${vm.kind.singular()}"
        !canManage -> vm.kind.singular().replaceFirstChar { it.uppercase() }
        else -> "Edit ${vm.kind.singular()}"
    }
    Scaffold(
        topBar = {
            Column {
                io.github.revocx35.npmmobile.ui.components.AppTopBar(title, onBack = { back() }, subtitle = vm.host?.title, actions = {
                    if (canManage && vm.loadError == null && !vm.loading) {
                        TextButton(onClick = { vm.save()?.let { tab = it } }, enabled = vm.busy == null, modifier = Modifier.testTag("save")) { Text("Save") }
                    }
                    val h = vm.host
                    if (h != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "More actions") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (openDomain != null) {
                                    DropdownMenuItem(
                                        text = { Text("Open in browser") }, leadingIcon = { Icon(AppIcons.OpenInNew, null) },
                                        onClick = { menu = false; siteUrl(openDomain, h.certificateId > 0)?.let(context::openUrl) },
                                    )
                                }
                                if (canManage) {
                                    DropdownMenuItem(
                                        text = { Text(if (h.enabled) "Disable" else "Enable") }, leadingIcon = { Icon(AppIcons.Power, null) },
                                        onClick = { menu = false; vm.setEnabled(!h.enabled) },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; confirmDelete = true },
                                    )
                                }
                            }
                        }
                    }
                })
                if (tabs.size > 1 && !vm.loading && vm.loadError == null) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("edittab-$t")) }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            vm.loading -> LoadingBox(Modifier.padding(padding))
            vm.loadError != null -> Column(Modifier.padding(padding).padding(16.dp)) { ErrorCard(vm.loadError!!.message, onRetry = { vm.load() }) }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vm.host?.let { h -> if (tab == 0) HostStatusHeader(h) }
                vm.saveError?.let { ErrorCard(it.message, details = it.details) }
                if (!canManage) InfoBanner("Your NPM user can view but not change ${vm.kind.label().lowercase()}.", icon = AppIcons.Lock)
                content(tab)
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    vm.busy?.let { BusyDialog(it) }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete ${vm.kind.singular()}?", "\"${vm.host?.title}\" will be removed from NPM and nginx. This can't be undone.", "Delete",
            onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
    if (confirmDiscard) {
        ConfirmDialog("Discard changes?", "Your changes haven't been saved.", "Discard", onConfirm = { nav.popBackStack() }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
}

@Composable
private fun HostStatusHeader(host: NpmHost) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusDot(host.status)
        Spacer(Modifier.width(8.dp))
        Text(host.status.label(), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        val created = when (host) {
            is io.github.revocx35.npmmobile.core.ProxyHost -> host.createdOn
            is io.github.revocx35.npmmobile.core.RedirectionHost -> host.createdOn
            is io.github.revocx35.npmmobile.core.Stream -> host.createdOn
            is io.github.revocx35.npmmobile.core.DeadHost -> host.createdOn
        }
        Text("Created ${Format.date(NpmDates.parse(created))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (host.status == HostStatus.Offline) {
        ErrorCard("nginx rejected the configuration for this host, so it isn't served.", details = host.nginxError, detailsExpanded = true)
    }
}
