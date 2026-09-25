package io.github.revocx35.npmmobile.ui.certificates

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.Certificate
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.ui.CertificateRoute
import io.github.revocx35.npmmobile.ui.CustomCertRoute
import io.github.revocx35.npmmobile.ui.Expiry
import io.github.revocx35.npmmobile.ui.ExpiryLevel
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.NewLetsEncryptRoute
import io.github.revocx35.npmmobile.ui.components.EmptyState
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.SearchableTopBar
import io.github.revocx35.npmmobile.ui.components.Tag
import io.github.revocx35.npmmobile.ui.expiryOf
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.theme.LocalStatusColors

@Composable
fun Expiry.color(): Color {
    val s = LocalStatusColors.current
    return when (level) {
        ExpiryLevel.Ok -> s.online
        ExpiryLevel.Soon -> s.warning
        ExpiryLevel.Critical, ExpiryLevel.Expired -> s.offline
        ExpiryLevel.Unknown -> s.disabled
    }
}

fun Certificate.providerLabel(): String = when {
    isIncomplete -> "Custom · no files uploaded"
    isLetsEncrypt && usesDnsChallenge -> "Let's Encrypt · DNS (${dnsProvider ?: "?"})"
    isLetsEncrypt -> "Let's Encrypt · HTTP"
    else -> "Custom"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertificatesScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val user = LocalSession.current.user
    val canManage = user?.canManage(Section.Certificates) ?: true
    val res by graph.repository.certificateList.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.repository.certificates.refresh() }

    Scaffold(
        topBar = { SearchableTopBar("SSL certificates", searching, query, { query = it }, { searching = it; if (!it) query = "" }, placeholder = "Search domains…") },
        floatingActionButton = {
            if (canManage) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        icon = { Icon(AppIcons.Add, null) },
                        text = { Text("Add certificate") },
                        modifier = Modifier.testTag("addCert"),
                    )
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Column { Text("Let's Encrypt"); Text("Free, renews automatically", style = MaterialTheme.typography.bodySmall) } },
                            leadingIcon = { Icon(AppIcons.Certificate, null) },
                            onClick = { addMenu = false; nav.navigate(NewLetsEncryptRoute) },
                        )
                        DropdownMenuItem(
                            text = { Column { Text("Custom certificate"); Text("Upload your own PEM files", style = MaterialTheme.typography.bodySmall) } },
                            leadingIcon = { Icon(AppIcons.UploadFile, null) },
                            onClick = { addMenu = false; nav.navigate(CustomCertRoute(0)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = res.loading && res.data != null, onRefresh = { graph.repository.certificates.refresh() }, modifier = Modifier.fillMaxSize().padding(padding)) {
            val q = query.trim().lowercase()
            val list = res.data.orEmpty()
                .filter { q.isEmpty() || (it.displayName + " " + it.domainNames.joinToString(" ")).lowercase().contains(q) }
                .sortedWith(compareBy<Certificate> { it.expiresAt ?: java.time.Instant.MAX })
            when {
                res.isInitialLoading -> LoadingBox()
                res.data == null && res.error != null -> Column(Modifier.padding(16.dp)) { ErrorCard(res.error!!, onRetry = { graph.repository.certificates.refresh() }) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    res.error?.let { e -> item { ErrorCard(e) } }
                    if (list.isEmpty()) {
                        item {
                            if (q.isNotEmpty()) EmptyState(AppIcons.Search, "No matches")
                            else EmptyState(AppIcons.Certificate, "No certificates yet", text = "Request a free Let's Encrypt certificate or upload your own.")
                        }
                    }
                    items(list, key = { it.id }) { cert -> CertificateCard(cert) { nav.navigate(CertificateRoute(cert.id)) } }
                }
            }
        }
    }
}

@Composable
private fun CertificateCard(cert: Certificate, onClick: () -> Unit) {
    val expiry = expiryOf(cert.expiresAt)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("cert-${cert.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (cert.isLetsEncrypt) AppIcons.Certificate else AppIcons.Key, null, tint = if (cert.isIncomplete) LocalStatusColors.current.disabled else expiry.color())
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(cert.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cert.niceName.isNotBlank() && cert.domainNames.isNotEmpty() && cert.niceName != cert.domainNames.joinToString(", ")) {
                    Text(cert.domainNames.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(cert.providerLabel(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!cert.isIncomplete) {
                    Row(Modifier.padding(top = 6.dp)) {
                        val c = expiry.color()
                        Tag(expiry.text, color = c.copy(alpha = 0.14f), contentColor = c, icon = AppIcons.Schedule)
                    }
                }
            }
        }
    }
}
