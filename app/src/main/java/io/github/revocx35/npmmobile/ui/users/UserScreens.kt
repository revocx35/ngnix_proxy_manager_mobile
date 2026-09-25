package io.github.revocx35.npmmobile.ui.users

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.core.User
import io.github.revocx35.npmmobile.core.UserPermissions
import io.github.revocx35.npmmobile.core.UserRequest
import io.github.revocx35.npmmobile.core.Validation
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.UiError
import io.github.revocx35.npmmobile.ui.UserRoute
import io.github.revocx35.npmmobile.ui.components.AppTopBar
import io.github.revocx35.npmmobile.ui.components.BusyDialog
import io.github.revocx35.npmmobile.ui.components.ConfirmDialog
import io.github.revocx35.npmmobile.ui.components.DropdownField
import io.github.revocx35.npmmobile.ui.components.EmptyState
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.FormCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.InitialsAvatar
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.PasswordField
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.SegmentedPicker
import io.github.revocx35.npmmobile.ui.components.SwitchRow
import io.github.revocx35.npmmobile.ui.components.Tag
import io.github.revocx35.npmmobile.ui.components.TonalButton
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.theme.LocalStatusColors
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val me = LocalSession.current.user
    val res by graph.repository.userList.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { graph.repository.users.refresh() }
    Scaffold(
        topBar = { AppTopBar("Users", onBack = { nav.popBackStack() }) },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate(UserRoute(0)) }) { Icon(AppIcons.PersonAdd, "Add user") } },
    ) { padding ->
        PullToRefreshBox(res.loading && res.data != null, { graph.repository.users.refresh() }, Modifier.fillMaxSize().padding(padding)) {
            when {
                res.isInitialLoading -> LoadingBox()
                res.data == null && res.error != null -> Column(Modifier.padding(16.dp)) { ErrorCard(res.error!!, onRetry = { graph.repository.users.refresh() }) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (res.data.isNullOrEmpty()) item { EmptyState(AppIcons.Users, "No users") }
                    items(res.data.orEmpty().sortedBy { it.displayName.lowercase() }, key = { it.id }) { u ->
                        Card(Modifier.fillMaxWidth().clickable { nav.navigate(UserRoute(u.id)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                InitialsAvatar(u.displayName)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.displayName + if (u.id == me?.id) " (you)" else "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(u.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Tag(if (u.isAdmin) "Administrator" else "User", icon = if (u.isAdmin) AppIcons.Admin else AppIcons.Person)
                                        if (u.isDisabled) {
                                            val c = LocalStatusColors.current.disabled
                                            Tag("Disabled", color = c.copy(alpha = 0.14f), contentColor = c)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class UserEditorViewModel(private val graph: AppGraph, val id: Int) : ViewModel() {
    var loading by mutableStateOf(id != 0)
        private set
    var loadError by mutableStateOf<UiError?>(null)
        private set
    var user by mutableStateOf<User?>(null)
        private set
    var name by mutableStateOf("")
    var nickname by mutableStateOf("")
    var email by mutableStateOf("")
    var admin by mutableStateOf(false)
    var disabled by mutableStateOf(false)
    var password by mutableStateOf("")
    var permissions by mutableStateOf(UserPermissions(visibility = "user"))
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var showErrors by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set

    init { if (id != 0) load() }

    fun load() {
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                val u = graph.session.call { it.user(id) }
                user = u
                name = u.name
                nickname = u.nickname
                email = u.email
                admin = u.isAdmin
                disabled = u.isDisabled
                permissions = u.permissions ?: UserPermissions()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadError = e.toUiError()
            } finally {
                loading = false
            }
        }
    }

    val nameError get() = if (name.isBlank()) "Enter a name" else null
    val emailError get() = Validation.emailError(email)
    val passwordError get() = if (id == 0 && password.length < 8) "At least 8 characters" else null

    private fun act(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }

    fun save() {
        showErrors = true
        if (nameError != null || emailError != null || passwordError != null) return
        val body = UserRequest(
            name = name.trim(), nickname = nickname.trim().ifBlank { name.trim().substringBefore(' ') }, email = email.trim(),
            roles = if (admin) listOf("admin") else emptyList(), isDisabled = disabled,
            auth = if (id == 0) UserRequest.passwordAuth(password) else null,
        )
        act("Saving…") {
            val saved = graph.session.call { if (id == 0) it.createUser(body) else it.updateUser(id, body) }
            if (!admin) graph.session.call { it.setPermissions(saved.id, permissions) }
            val myId = (graph.session.state.value as? io.github.revocx35.npmmobile.data.SessionState.SignedIn)?.user?.id
            if (saved.id == myId) graph.session.updateUser(saved)
            graph.repository.users.load()
            done = true
        }
    }

    fun changePasswordTo(newPassword: String) = act("Setting password…") {
        graph.session.call { it.changePassword(id, newPassword, current = null) }
        message = "Password changed"
    }

    fun delete() = act("Deleting…") {
        graph.session.call { it.deleteUser(id) }
        graph.repository.users.load()
        done = true
    }
}

private val LEVEL_OPTIONS = listOf(
    Triple(UserPermissions.MANAGE, "Manage", null),
    Triple(UserPermissions.VIEW, "View only", null),
    Triple(UserPermissions.HIDDEN, "Hidden", null),
)

@Composable
fun UserEditor(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val me = LocalSession.current.user
    val vm: UserEditorViewModel = viewModel(key = "user-$id") { UserEditorViewModel(graph, id) }
    var confirmDelete by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val isSelf = id != 0 && id == me?.id
    LaunchedEffect(vm.done) { if (vm.done) nav.popBackStack() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    val err = vm.showErrors

    Scaffold(
        topBar = {
            AppTopBar(if (id == 0) "New user" else "Edit user", onBack = { nav.popBackStack() }, actions = {
                if (!vm.loading && vm.loadError == null) {
                    if (id != 0 && !isSelf) IconButton(onClick = { confirmDelete = true }) { Icon(AppIcons.Delete, "Delete user") }
                    TextButton(onClick = { vm.save() }) { Text("Save") }
                }
            })
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
                vm.error?.let { ErrorCard(it.message, details = it.details) }
                FormTextField(vm.name, { vm.name = it }, "Full name", error = if (err) vm.nameError else null)
                FormTextField(vm.nickname, { vm.nickname = it }, "Nickname", supportingText = "Shown in the NPM UI")
                FormTextField(vm.email, { vm.email = it }, "E-mail", error = if (err) vm.emailError else null, keyboardType = androidx.compose.ui.text.input.KeyboardType.Email)
                if (id == 0) PasswordField(vm.password, { vm.password = it }, "Password", error = if (err) vm.passwordError else null)
                FormCard {
                    SwitchRow("Administrator", vm.admin, { vm.admin = it }, "Full access to everything, including users and settings", enabled = !isSelf, icon = AppIcons.Admin)
                    SwitchRow("Disabled", vm.disabled, { vm.disabled = it }, "Can't sign in", enabled = !isSelf, icon = AppIcons.Lock)
                }
                if (id != 0) TonalButton("Set a new password", { passwordDialog = true }, Modifier.fillMaxWidth(), icon = AppIcons.Key)

                SectionHeader("Permissions", inset = false)
                if (vm.admin) {
                    InfoBanner("Administrators can manage everything.", icon = AppIcons.Admin)
                } else {
                    val p = vm.permissions
                    Text("Item visibility", style = MaterialTheme.typography.bodyMedium)
                    SegmentedPicker(listOf("user" to "Own items only", "all" to "All items"), p.visibility, { vm.permissions = p.copy(visibility = it) })
                    DropdownField("Proxy hosts", LEVEL_OPTIONS, p.proxyHosts, { vm.permissions = p.copy(proxyHosts = it) }, leadingIcon = AppIcons.Hosts)
                    DropdownField("Redirection hosts", LEVEL_OPTIONS, p.redirectionHosts, { vm.permissions = p.copy(redirectionHosts = it) }, leadingIcon = AppIcons.Redirect)
                    DropdownField("Streams", LEVEL_OPTIONS, p.streams, { vm.permissions = p.copy(streams = it) }, leadingIcon = AppIcons.Stream)
                    DropdownField("404 hosts", LEVEL_OPTIONS, p.deadHosts, { vm.permissions = p.copy(deadHosts = it) }, leadingIcon = AppIcons.Dead)
                    DropdownField("Access lists", LEVEL_OPTIONS, p.accessLists, { vm.permissions = p.copy(accessLists = it) }, leadingIcon = AppIcons.AccessList)
                    DropdownField("Certificates", LEVEL_OPTIONS, p.certificates, { vm.permissions = p.copy(certificates = it) }, leadingIcon = AppIcons.Certificate)
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
    vm.busy?.let { BusyDialog(it) }
    if (confirmDelete) {
        ConfirmDialog("Delete user?", "${vm.name} will be deleted. Their hosts and certificates stay.", "Delete", { vm.delete() }, { confirmDelete = false }, destructive = true)
    }
    if (passwordDialog) {
        SetPasswordDialog(onDismiss = { passwordDialog = false }) { vm.changePasswordTo(it) }
    }
}

@Composable
fun SetPasswordDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val error = when {
        pw.isNotEmpty() && pw.length < 8 -> "At least 8 characters"
        confirm.isNotEmpty() && confirm != pw -> "Passwords don't match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a new password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(pw, { pw = it }, "New password")
                PasswordField(confirm, { confirm = it }, "Repeat password", error = error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(pw); onDismiss() }, enabled = pw.length >= 8 && pw == confirm) { Text("Set password") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
