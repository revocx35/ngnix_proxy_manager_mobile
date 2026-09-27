package io.github.revocx35.npmmobile.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.DeadHost
import io.github.revocx35.npmmobile.core.HostKind
import io.github.revocx35.npmmobile.core.HostStatus
import io.github.revocx35.npmmobile.core.NpmHost
import io.github.revocx35.npmmobile.core.ProxyHost
import io.github.revocx35.npmmobile.core.RedirectionHost
import io.github.revocx35.npmmobile.core.Stream
import io.github.revocx35.npmmobile.data.Resource
import io.github.revocx35.npmmobile.ui.DeadHostRoute
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.ProxyHostRoute
import io.github.revocx35.npmmobile.ui.RedirectionHostRoute
import io.github.revocx35.npmmobile.ui.StreamRoute
import io.github.revocx35.npmmobile.ui.components.AppTopBar
import io.github.revocx35.npmmobile.ui.components.SearchableTopBar
import io.github.revocx35.npmmobile.ui.components.ConfirmDialog
import io.github.revocx35.npmmobile.ui.components.EmptyState
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.StatusDot
import io.github.revocx35.npmmobile.ui.components.Tag
import io.github.revocx35.npmmobile.ui.components.label
import io.github.revocx35.npmmobile.ui.openUrl
import io.github.revocx35.npmmobile.ui.siteUrl
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

fun HostKind.label() = when (this) {
    HostKind.Proxy -> "Proxy hosts"
    HostKind.Redirection -> "Redirections"
    HostKind.Stream -> "Streams"
    HostKind.Dead -> "404 hosts"
}

/** Short name for the tabs, so all four fit on a phone. */
fun HostKind.tabLabel() = when (this) {
    HostKind.Proxy -> "Proxy"
    HostKind.Redirection -> "Redirect"
    HostKind.Stream -> "Stream"
    HostKind.Dead -> "404"
}

fun HostKind.singular() = when (this) {
    HostKind.Proxy -> "proxy host"
    HostKind.Redirection -> "redirection host"
    HostKind.Stream -> "stream"
    HostKind.Dead -> "404 host"
}

fun HostKind.icon() = when (this) {
    HostKind.Proxy -> AppIcons.Hosts
    HostKind.Redirection -> AppIcons.Redirect
    HostKind.Stream -> AppIcons.Stream
    HostKind.Dead -> AppIcons.Dead
}

fun HostKind.editRoute(id: Int): Any = when (this) {
    HostKind.Proxy -> ProxyHostRoute(id)
    HostKind.Redirection -> RedirectionHostRoute(id)
    HostKind.Stream -> StreamRoute(id)
    HostKind.Dead -> DeadHostRoute(id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(nav: NavHostController, initialKind: Int) {
    val graph = LocalGraph.current
    val user = LocalSession.current.user
    val kinds = HostKind.entries.filter { user?.canView(it.section) ?: true }
    val actions: HostActionsViewModel = viewModel { HostActionsViewModel(graph) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = kinds.indexOfFirst { it.ordinal == initialKind }.coerceAtLeast(0)) { kinds.size }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(actions.message) {
        actions.message?.let {
            snackbar.showSnackbar(it)
            actions.message = null
        }
    }

    if (kinds.isEmpty()) {
        Scaffold(topBar = { AppTopBar("Hosts") }) { p ->
            EmptyState(AppIcons.Lock, "No access", Modifier.padding(p), text = "Your NPM user can't see any host type.")
        }
        return
    }
    val currentKind = kinds[pager.currentPage.coerceIn(0, kinds.lastIndex)]

    Scaffold(
        topBar = {
            Column {
                SearchableTopBar("Hosts", searching, query, onQuery = { query = it }, onSearching = { searching = it; if (!it) query = "" })
                PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                    kinds.forEachIndexed { i, kind ->
                        val count = graph.repository.hosts(kind).state.collectAsStateWithLifecycle().value.data?.size
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(kind.tabLabel(), maxLines = 1)
                                    if (count != null) {
                                        Spacer(Modifier.width(4.dp))
                                        Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) { Text("$count") }
                                    }
                                }
                            },
                            modifier = Modifier.testTag("hosttab-${kind.name}"),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (user?.canManage(currentKind.section) != false) {
                FloatingActionButton(onClick = { nav.navigate(currentKind.editRoute(0)) }, modifier = Modifier.testTag("addHost")) {
                    Icon(AppIcons.Add, "Add ${currentKind.singular()}")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 1) { page ->
            val kind = kinds[page]
            HostList(kind, query, nav, actions, canManage = user?.canManage(kind.section) != false)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostList(kind: HostKind, query: String, nav: NavHostController, actions: HostActionsViewModel, canManage: Boolean) {
    val graph = LocalGraph.current
    val cached = graph.repository.hosts(kind)
    @Suppress("UNCHECKED_CAST")
    val res by (cached.state as kotlinx.coroutines.flow.StateFlow<Resource<List<NpmHost>>>).collectAsStateWithLifecycle()
    LaunchedEffect(kind) { cached.refresh() }
    var confirmDelete by remember { mutableStateOf<NpmHost?>(null) }

    val q = query.trim().lowercase()
    val hosts = res.data.orEmpty()
        .filter { q.isEmpty() || it.searchText().contains(q) }
        .sortedWith(compareBy<NpmHost> { it.status == HostStatus.Disabled }.thenBy { it.title })

    PullToRefreshBox(isRefreshing = res.loading && res.data != null, onRefresh = { cached.refresh() }, modifier = Modifier.fillMaxSize()) {
        when {
            res.isInitialLoading -> LoadingBox()
            res.data == null && res.error != null -> Column(Modifier.padding(16.dp)) { ErrorCard(res.error!!, onRetry = { cached.refresh() }) }
            else -> LazyColumn(Modifier.fillMaxSize().testTag("hostlist-${kind.name}"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                res.error?.let { e -> item { ErrorCard(e, onRetry = { cached.refresh() }) } }
                if (hosts.isEmpty()) {
                    item {
                        if (q.isNotEmpty()) {
                            EmptyState(AppIcons.Search, "No matches", text = "Nothing matches \"$query\".")
                        } else {
                            EmptyState(kind.icon(), "No ${kind.label().lowercase()} yet", text = if (canManage) "Tap + to add one." else null)
                        }
                    }
                }
                items(hosts, key = { it.id }) { host ->
                    HostCard(
                        host = host,
                        busy = actions.busy.contains(kind to host.id),
                        canManage = canManage,
                        onClick = { nav.navigate(kind.editRoute(host.id)) },
                        onToggle = { actions.setEnabled(host, !host.enabled) },
                        onDelete = { confirmDelete = host },
                    )
                }
            }
        }
    }
    confirmDelete?.let { host ->
        ConfirmDialog(
            title = "Delete ${kind.singular()}?",
            text = "\"${host.title}\" will be removed from NPM and nginx. This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { actions.delete(host) },
            onDismiss = { confirmDelete = null },
        )
    }
}

private fun NpmHost.searchText(): String = when (this) {
    is ProxyHost -> domainNames.joinToString(" ") + " " + target + " " + (accessList?.name ?: "")
    is RedirectionHost -> domainNames.joinToString(" ") + " " + target + " " + forwardHttpCode
    is Stream -> "$incomingPort $target $protocols"
    is DeadHost -> domainNames.joinToString(" ")
}.lowercase()

@Composable
fun HostCard(host: NpmHost, busy: Boolean, canManage: Boolean, onClick: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val status = LocalStatusColors.current
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("host-${host.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else StatusDot(host.status)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val domains = (host as? ProxyHost)?.domainNames ?: (host as? RedirectionHost)?.domainNames ?: (host as? DeadHost)?.domainNames
                Text(
                    if (domains != null) domains.firstOrNull().orEmpty() + if (domains.size > 1) "  +${domains.size - 1}" else "" else host.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (host.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (host) {
                        is ProxyHost -> "→ ${host.target}"
                        is RedirectionHost -> "→ ${host.forwardHttpCode} · ${host.target}"
                        is Stream -> "${host.protocols} → ${host.target}"
                        is DeadHost -> "Serves a 404 page"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val tags = buildList {
                    if (host.status == HostStatus.Offline) add(Triple("Offline", status.offline, AppIcons.Error))
                    if (host.status == HostStatus.Disabled) add(Triple("Disabled", status.disabled, AppIcons.Power))
                    if (host.certificateId > 0) add(Triple(if ((host as? ProxyHost)?.sslForced == true || (host as? RedirectionHost)?.sslForced == true || (host as? DeadHost)?.sslForced == true) "HTTPS only" else "SSL", status.online, AppIcons.Lock))
                    (host as? ProxyHost)?.accessList?.let { add(Triple(it.name, MaterialTheme.colorScheme.tertiary, AppIcons.AccessList)) }
                    if ((host as? ProxyHost)?.locations?.isNotEmpty() == true) add(Triple(io.github.revocx35.npmmobile.ui.Format.plural(host.locations.size, "location"), MaterialTheme.colorScheme.secondary, AppIcons.Route))
                }
                if (tags.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.take(3).forEach { (text, color, icon) -> Tag(text, color = color.copy(alpha = 0.14f), contentColor = color, icon = icon) }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("hostmenu-${host.id}")) { Icon(AppIcons.MoreVert, "Actions for ${host.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val domain = ((host as? ProxyHost)?.domainNames ?: (host as? RedirectionHost)?.domainNames ?: (host as? DeadHost)?.domainNames)?.firstOrNull { !it.startsWith("*") }
                    if (domain != null) {
                        DropdownMenuItem(
                            text = { Text("Open $domain") },
                            leadingIcon = { Icon(AppIcons.OpenInNew, null) },
                            onClick = { menu = false; siteUrl(domain, host.certificateId > 0)?.let(context::openUrl) },
                        )
                    }
                    if (canManage) {
                        DropdownMenuItem(
                            text = { Text(if (host.enabled) "Disable" else "Enable") },
                            leadingIcon = { Icon(AppIcons.Power, null) },
                            onClick = { menu = false; onToggle() },
                            modifier = Modifier.testTag("toggle-${host.id}"),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/** Status text for accessibility and the editor header. */
fun NpmHost.statusText(): String = status.label() + (nginxError?.let { ": ${it.lineSequence().first()}" } ?: "")
