package io.github.revocx35.npmmobile.ui.access

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.AccessList
import io.github.revocx35.npmmobile.core.AccessListClientRequest
import io.github.revocx35.npmmobile.core.AccessListItemRequest
import io.github.revocx35.npmmobile.core.AccessListRequest
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.core.Validation
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.ui.AccessListRoute
import io.github.revocx35.npmmobile.ui.Format
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.UiError
import io.github.revocx35.npmmobile.ui.components.AppTopBar
import io.github.revocx35.npmmobile.ui.components.BusyDialog
import io.github.revocx35.npmmobile.ui.components.ConfirmDialog
import io.github.revocx35.npmmobile.ui.components.DropdownField
import io.github.revocx35.npmmobile.ui.components.EmptyState
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.FormCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.PasswordField
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.SwitchRow
import io.github.revocx35.npmmobile.ui.components.Tag
import io.github.revocx35.npmmobile.ui.components.TonalButton
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessListsScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val canManage = LocalSession.current.user?.canManage(Section.AccessLists) ?: true
    val res by graph.repository.accessListList.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { graph.repository.accessLists.refresh() }
    Scaffold(
        topBar = { AppTopBar("Access lists", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            if (canManage) FloatingActionButton(onClick = { nav.navigate(AccessListRoute(0)) }) { Icon(AppIcons.Add, "Add access list") }
        },
    ) { padding ->
        PullToRefreshBox(res.loading && res.data != null, { graph.repository.accessLists.refresh() }, Modifier.fillMaxSize().padding(padding)) {
            when {
                res.isInitialLoading -> LoadingBox()
                res.data == null && res.error != null -> Column(Modifier.padding(16.dp)) { ErrorCard(res.error!!, onRetry = { graph.repository.accessLists.refresh() }) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(
                            "Access lists protect proxy hosts with a username/password (basic auth) and/or IP address rules.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    if (res.data.isNullOrEmpty()) item { EmptyState(AppIcons.AccessList, "No access lists yet", text = if (canManage) "Tap + to create one." else null) }
                    items(res.data.orEmpty(), key = { it.id }) { acl -> AccessListCard(acl) { nav.navigate(AccessListRoute(acl.id)) } }
                }
            }
        }
    }
}

@Composable
private fun AccessListCard(acl: AccessList, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.AccessList, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(acl.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.plural(acl.items.size, "user")} · ${Format.plural(acl.clients.size, "rule")} · used by ${Format.plural(acl.proxyHostCount, "host")}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(if (acl.satisfyAny) "Satisfy any" else "Satisfy all")
                    if (acl.passAuth) Tag("Passes auth upstream")
                }
            }
        }
    }
}

class AuthItemForm(val key: String = UUID.randomUUID().toString(), existing: Boolean = false) {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var hint by mutableStateOf<String?>(null)
    val isExisting = existing
    val usernameError get() = if (username.isBlank()) "Enter a username" else null
    val passwordError get() = if (!isExisting && password.isEmpty()) "Enter a password" else null
}

class ClientRuleForm(val key: String = UUID.randomUUID().toString()) {
    var directive by mutableStateOf("allow")
    var address by mutableStateOf("")
    val addressError get() = Validation.clientAddressError(address)
}

class AccessListEditorViewModel(private val graph: AppGraph, val id: Int) : ViewModel() {
    var loading by mutableStateOf(id != 0)
        private set
    var loadError by mutableStateOf<UiError?>(null)
        private set
    var name by mutableStateOf("")
    var satisfyAny by mutableStateOf(false)
    var passAuth by mutableStateOf(false)
    val items = mutableStateListOf<AuthItemForm>()
    val clients = mutableStateListOf<ClientRuleForm>()
    var usedBy by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    var showErrors by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    private var initial: String? = null

    init {
        if (id != 0) load() else initial = snapshot()
    }

    fun load() {
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                val acl = graph.session.call { it.accessList(id) }
                name = acl.name
                satisfyAny = acl.satisfyAny
                passAuth = acl.passAuth
                usedBy = acl.proxyHostCount
                items.clear()
                acl.items.forEach { i -> items += AuthItemForm(existing = true).apply { username = i.username; hint = i.hint } }
                clients.clear()
                acl.clients.forEach { c -> clients += ClientRuleForm().apply { directive = c.directive; address = c.address } }
                initial = snapshot()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadError = e.toUiError()
            } finally {
                loading = false
            }
        }
    }

    fun snapshot() = listOf(name, satisfyAny, passAuth, items.joinToString { it.username + ":" + it.password }, clients.joinToString { it.directive + it.address }).joinToString("§")
    val dirty get() = initial != null && initial != snapshot()

    val nameError get() = if (name.isBlank()) "Enter a name" else null
    private val valid get() = nameError == null && items.all { it.usernameError == null && it.passwordError == null } && clients.all { it.addressError == null }

    fun save() {
        showErrors = true
        if (!valid || busy != null) return
        if (items.isEmpty() && clients.isEmpty()) {
            error = UiError("Add at least one user or one IP rule.")
            return
        }
        busy = "Saving…"
        error = null
        val body = AccessListRequest(
            name = name.trim(), satisfyAny = satisfyAny, passAuth = passAuth,
            items = items.map { AccessListItemRequest(it.username.trim(), it.password) },
            clients = clients.map { AccessListClientRequest(it.address.trim(), it.directive) },
        )
        viewModelScope.launch {
            try {
                graph.session.call { if (id == 0) it.createAccessList(body) else it.updateAccessList(id, body) }
                graph.repository.accessLists.load()
                done = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }

    fun delete() {
        busy = "Deleting…"
        viewModelScope.launch {
            try {
                graph.session.call { it.deleteAccessList(id) }
                graph.repository.accessLists.load()
                graph.repository.proxyHosts.refresh()
                done = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }
}

@Composable
fun AccessListEditor(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val canManage = LocalSession.current.user?.canManage(Section.AccessLists) ?: true
    val vm: AccessListEditorViewModel = viewModel(key = "acl-$id") { AccessListEditorViewModel(graph, id) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(vm.done) { if (vm.done) nav.popBackStack() }
    BackHandler(vm.dirty && canManage) { confirmDiscard = true }
    val err = vm.showErrors

    Scaffold(
        topBar = {
            AppTopBar(if (id == 0) "New access list" else "Edit access list", onBack = { if (vm.dirty && canManage) confirmDiscard = true else nav.popBackStack() }, actions = {
                if (canManage && !vm.loading && vm.loadError == null) {
                    if (id != 0) IconButton(onClick = { confirmDelete = true }) { Icon(AppIcons.Delete, "Delete") }
                    TextButton(onClick = { vm.save() }, modifier = Modifier.testTag("save")) { Text("Save") }
                }
            })
        },
    ) { padding ->
        when {
            vm.loading -> LoadingBox(Modifier.padding(padding))
            vm.loadError != null -> Column(Modifier.padding(padding).padding(16.dp)) { ErrorCard(vm.loadError!!.message, onRetry = { vm.load() }) }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vm.error?.let { ErrorCard(it.message, details = it.details) }
                FormTextField(vm.name, { vm.name = it }, "Name", error = if (err) vm.nameError else null, modifier = Modifier.testTag("aclName"))
                FormCard {
                    SwitchRow("Satisfy any", vm.satisfyAny, { vm.satisfyAny = it }, if (vm.satisfyAny) "A valid login OR an allowed IP is enough" else "Requires a valid login AND an allowed IP")
                    SwitchRow("Pass auth to host", vm.passAuth, { vm.passAuth = it }, "Forward the Authorization header to the upstream service")
                }

                SectionHeader("Authorization users", inset = false)
                vm.items.forEachIndexed { i, item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("User ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.items.remove(item) }) { Icon(AppIcons.Delete, "Remove user") }
                            }
                            FormTextField(item.username, { item.username = it }, "Username", error = if (err) item.usernameError else null)
                            PasswordField(
                                item.password, { item.password = it }, "Password",
                                error = if (err) item.passwordError else null,
                                supportingText = if (item.isExisting) "Leave empty to keep the current password${item.hint?.let { " ($it)" } ?: ""}" else null,
                            )
                        }
                    }
                }
                TonalButton("Add user", { vm.items += AuthItemForm() }, Modifier.fillMaxWidth(), icon = AppIcons.PersonAdd)

                SectionHeader("IP address rules", inset = false)
                if (vm.clients.isNotEmpty()) InfoBanner("Rules are checked in order. Add \"deny all\" at the end to block everyone else.")
                vm.clients.forEach { rule ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DropdownField(
                                "Rule", listOf(Triple("allow", "Allow", null), Triple("deny", "Deny", null)), rule.directive, { rule.directive = it },
                                modifier = Modifier.weight(0.38f),
                            )
                            FormTextField(rule.address, { rule.address = it }, "IP / range", error = if (err) rule.addressError else null, placeholder = "10.0.0.0/8", modifier = Modifier.weight(0.62f))
                            IconButton(onClick = { vm.clients.remove(rule) }, modifier = Modifier.padding(top = 4.dp)) { Icon(AppIcons.Delete, "Remove rule") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("Add rule", { vm.clients += ClientRuleForm() }, Modifier.weight(1f), icon = AppIcons.Add)
                    TonalButton("Deny all", { vm.clients += ClientRuleForm().apply { directive = "deny"; address = "all" } }, Modifier.weight(1f), icon = AppIcons.Dead)
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
    vm.busy?.let { BusyDialog(it) }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete access list?",
            if (vm.usedBy > 0) "${Format.plural(vm.usedBy, "host")} use this list and will become publicly accessible." else "\"${vm.name}\" will be deleted.",
            "Delete", onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
    if (confirmDiscard) ConfirmDialog("Discard changes?", "Your changes haven't been saved.", "Discard", { nav.popBackStack() }, { confirmDiscard = false }, destructive = true)
}
