package io.github.revocx35.npmmobile.ui.audit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.AuditLogEntry
import io.github.revocx35.npmmobile.core.NpmDates
import io.github.revocx35.npmmobile.ui.Format
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.components.EmptyState
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.SearchableTopBar
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.theme.LocalStatusColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val PrettyJson = Json { prettyPrint = true }

private fun objectName(type: String) = when (type) {
    "proxy-host" -> "proxy host"
    "redirection-host" -> "redirection host"
    "dead-host" -> "404 host"
    "stream" -> "stream"
    "access-list" -> "access list"
    "certificate" -> "certificate"
    "user" -> "user"
    "setting" -> "setting"
    else -> type.replace('-', ' ')
}

private fun typeIcon(type: String) = when (type) {
    "proxy-host" -> AppIcons.Hosts
    "redirection-host" -> AppIcons.Redirect
    "dead-host" -> AppIcons.Dead
    "stream" -> AppIcons.Stream
    "access-list" -> AppIcons.AccessList
    "certificate" -> AppIcons.Certificate
    "user" -> AppIcons.Person
    else -> AppIcons.Settings
}

@Composable
private fun actionColor(action: String): Color {
    val s = LocalStatusColors.current
    return when (action) {
        "created", "enabled", "renewed" -> s.online
        "deleted", "disabled" -> s.offline
        else -> MaterialTheme.colorScheme.primary
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val res by graph.repository.auditLog.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AuditLogEntry?>(null) }
    LaunchedEffect(Unit) { graph.repository.auditLog.refresh() }

    Scaffold(topBar = {
        SearchableTopBar("Audit log", searching, query, { query = it }, { searching = it; if (!it) query = "" }, onBack = { nav.popBackStack() }, placeholder = "Filter by name, user, action…")
    }) { padding ->
        PullToRefreshBox(res.loading && res.data != null, { graph.repository.auditLog.refresh() }, Modifier.fillMaxSize().padding(padding)) {
            val q = query.trim().lowercase()
            val list = res.data.orEmpty().filter {
                q.isEmpty() || listOf(it.objectLabel, it.user?.displayName.orEmpty(), it.action, objectName(it.objectType)).any { s -> s.lowercase().contains(q) }
            }
            when {
                res.isInitialLoading -> LoadingBox()
                res.data == null && res.error != null -> Column(Modifier.padding(16.dp)) { ErrorCard(res.error!!, onRetry = { graph.repository.auditLog.refresh() }) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (list.isEmpty()) item { EmptyState(AppIcons.History, if (q.isEmpty()) "No events yet" else "No matches") }
                    items(list, key = { it.id }) { e ->
                        Row(Modifier.fillMaxWidth().clickable { selected = e }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = actionColor(e.action).copy(alpha = 0.14f), modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(typeIcon(e.objectType), null, Modifier.size(20.dp), tint = actionColor(e.action)) }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.objectLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${e.user?.displayName ?: "Someone"} ${e.action} ${objectName(e.objectType)}",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(Format.ago(NpmDates.parse(e.createdOn)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                    if (list.size >= 100) {
                        item { Text("NPM keeps the newest 100 entries in this view.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }

    selected?.let { e ->
        val pretty = remember(e.id) {
            // Hide secrets NPM may keep in the event data before showing it.
            val redacted = JsonObject(e.meta.filterKeys { it !in setOf("dns_provider_credentials", "certificate_key") })
            PrettyJson.encodeToString(JsonObject.serializer(), redacted)
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${e.action.replaceFirstChar { it.uppercase() }} ${objectName(e.objectType)}") },
            text = {
                Column {
                    Text("${e.user?.displayName ?: "Unknown user"} · ${Format.dateTime(NpmDates.parse(e.createdOn))}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                        Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                            Text(pretty, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}
