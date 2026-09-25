package io.github.revocx35.npmmobile.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.Certificate
import io.github.revocx35.npmmobile.core.HostStatus
import io.github.revocx35.npmmobile.core.NpmHost
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.ui.CertificateRoute
import io.github.revocx35.npmmobile.ui.DeadHostRoute
import io.github.revocx35.npmmobile.ui.ExpiryLevel
import io.github.revocx35.npmmobile.ui.HostsRoute
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.ProxyHostRoute
import io.github.revocx35.npmmobile.ui.RedirectionHostRoute
import io.github.revocx35.npmmobile.ui.StreamRoute
import io.github.revocx35.npmmobile.ui.components.AppTopBar
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.InitialsAvatar
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.Tag
import io.github.revocx35.npmmobile.ui.expiryOf
import io.github.revocx35.npmmobile.ui.navigateTopLevel
import io.github.revocx35.npmmobile.ui.openUrl
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val repo = graph.repository
    val session = LocalSession.current
    val user = session.user
    val report by repo.hostReport.collectAsStateWithLifecycle()
    val proxies by repo.proxyHostList.collectAsStateWithLifecycle()
    val redirections by repo.redirectionList.collectAsStateWithLifecycle()
    val streams by repo.streamList.collectAsStateWithLifecycle()
    val deads by repo.deadHostList.collectAsStateWithLifecycle()
    val certs by repo.certificateList.collectAsStateWithLifecycle()
    val version by repo.versionCheck.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun canView(s: Section) = user?.canView(s) ?: true
    fun refreshAll() {
        repo.report.refresh()
        repo.versionCheck.refresh()
        if (canView(Section.ProxyHosts)) repo.proxyHosts.refresh()
        if (canView(Section.RedirectionHosts)) repo.redirectionHosts.refresh()
        if (canView(Section.Streams)) repo.streams.refresh()
        if (canView(Section.DeadHosts)) repo.deadHosts.refresh()
        if (canView(Section.Certificates)) repo.certificates.refresh()
    }
    LaunchedEffect(session.account.id, user?.id) { refreshAll() }

    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { AppTopBar("Dashboard", subtitle = session.account.host, scrollBehavior = scroll) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = report.loading,
            onRefresh = { refreshAll() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            InitialsAvatar(user?.displayName ?: session.account.label, size = 48, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Hi, ${user?.nickname?.ifBlank { null } ?: user?.displayName ?: session.account.label}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(session.account.host, session.account.npmVersion?.let { "NPM $it" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (user?.isAdmin == true) Tag("Admin", icon = AppIcons.Admin)
                        }
                    }
                }
                version.data?.takeIf { it.updateAvailable }?.let { v ->
                    item {
                        Card(
                            modifier = Modifier.clickable { context.openUrl("https://github.com/NginxProxyManager/nginx-proxy-manager/releases") },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.Update, null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("NPM ${v.latest} is available", style = MaterialTheme.typography.titleSmall)
                                    Text("This server runs ${v.current}. Tap for release notes.", style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(AppIcons.OpenInNew, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
                report.error?.let { err -> item { ErrorCard(err, onRetry = { refreshAll() }) } }
                item {
                    val r = report.data
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile("Proxy hosts", r?.proxy, AppIcons.Hosts, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { nav.navigateTopLevel(HostsRoute(0)) }
                            StatTile("Redirections", r?.redirection, AppIcons.Redirect, Color(0xFF8E5CD9), Modifier.weight(1f)) { nav.navigateTopLevel(HostsRoute(1)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile("Streams", r?.stream, AppIcons.Stream, Color(0xFF1E88A8), Modifier.weight(1f)) { nav.navigateTopLevel(HostsRoute(2)) }
                            StatTile("404 hosts", r?.dead, AppIcons.Dead, Color(0xFFC0632A), Modifier.weight(1f)) { nav.navigateTopLevel(HostsRoute(3)) }
                        }
                    }
                }

                // Problems worth a look: hosts whose nginx config failed, and certificates that expire soon.
                val offline: List<NpmHost> = listOfNotNull(proxies.data, redirections.data, streams.data, deads.data).flatten().filter { it.status == HostStatus.Offline }
                val disabledCount = listOfNotNull(proxies.data, redirections.data, streams.data, deads.data).flatten().count { it.status == HostStatus.Disabled }
                val expiring: List<Certificate> = certs.data.orEmpty()
                    .filter { !it.isIncomplete && expiryOf(it.expiresAt).level in setOf(ExpiryLevel.Critical, ExpiryLevel.Soon, ExpiryLevel.Expired) }
                    .sortedBy { it.expiresAt }
                item { SectionHeader("Needs attention", inset = false) }
                if (offline.isEmpty() && expiring.isEmpty()) {
                    item {
                        AttentionRow(AppIcons.CheckCircle, LocalStatusColors.current.online, "All good", "No offline hosts and no certificates expiring in the next 3 weeks." + if (disabledCount > 0) " ($disabledCount disabled host${if (disabledCount == 1) "" else "s"})" else "") {}
                    }
                }
                offline.forEach { host ->
                    item(key = "off-${host.kind}-${host.id}") {
                        AttentionRow(AppIcons.Error, LocalStatusColors.current.offline, host.title, host.nginxError?.lineSequence()?.firstOrNull() ?: "nginx configuration error") {
                            nav.navigate(
                                when (host.kind) {
                                    io.github.revocx35.npmmobile.core.HostKind.Proxy -> ProxyHostRoute(host.id)
                                    io.github.revocx35.npmmobile.core.HostKind.Redirection -> RedirectionHostRoute(host.id)
                                    io.github.revocx35.npmmobile.core.HostKind.Stream -> StreamRoute(host.id)
                                    io.github.revocx35.npmmobile.core.HostKind.Dead -> DeadHostRoute(host.id)
                                },
                            )
                        }
                    }
                }
                expiring.forEach { cert ->
                    item(key = "cert-${cert.id}") {
                        val e = expiryOf(cert.expiresAt)
                        val color = if (e.level == ExpiryLevel.Soon) LocalStatusColors.current.warning else LocalStatusColors.current.offline
                        AttentionRow(AppIcons.Certificate, color, cert.displayName, e.text + if (cert.isLetsEncrypt) " · renews automatically" else " · custom, renew manually") {
                            nav.navigate(CertificateRoute(cert.id))
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, count: Int?, icon: ImageVector, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = accent)
            Spacer(Modifier.height(12.dp))
            Text(count?.toString() ?: "–", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttentionRow(icon: ImageVector, tint: Color, title: String, text: String, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
