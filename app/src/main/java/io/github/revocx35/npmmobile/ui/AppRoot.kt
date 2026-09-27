package io.github.revocx35.npmmobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.data.SessionState
import io.github.revocx35.npmmobile.ui.login.LoginScreen
import io.github.revocx35.npmmobile.ui.theme.NpmTheme

@Composable
fun AppRoot(graph: AppGraph) {
    val theme by graph.prefs.theme.collectAsStateWithLifecycle()
    val dynamicColor by graph.prefs.dynamicColor.collectAsStateWithLifecycle()
    NpmTheme(theme, dynamicColor) {
        CompositionLocalProvider(LocalGraph provides graph) {
            val state by graph.session.state.collectAsStateWithLifecycle()
            val accounts by graph.session.accounts.collectAsStateWithLifecycle()
            LifecycleResumeEffect(Unit) {
                graph.session.validateIfStale()
                onPauseOrDispose { }
            }
            // Test tags double as resource ids so UI Automator (scripts/e2e-release.py) can drive release builds.
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.surface) {
                when (val s = state) {
                    SessionState.Starting -> Box(Modifier.fillMaxSize())
                    SessionState.NoAccount -> LoginScreen(
                        prefill = null,
                        reason = null,
                        onCancel = if (accounts.isNotEmpty()) ({ graph.session.cancelAddAnother() }) else null,
                    )
                    is SessionState.NeedsSignIn -> key(s.account.id) { LoginScreen(prefill = s.account, reason = s.reason, onCancel = null) }
                    is SessionState.SignedIn -> key(s.account.id) { MainShell(s) }
                }
            }
            CrashReportPrompt(graph)
        }
    }
}

/** After a crash, offers once to open the report (Settings → Crash reports) so it can be shared. */
@Composable
private fun CrashReportPrompt(graph: AppGraph) {
    var pending by remember { mutableStateOf(graph.diagnostics.unseen() != null) }
    if (!pending) return
    AlertDialog(
        onDismissRequest = { pending = false; graph.diagnostics.markSeen() },
        title = { Text("The app stopped unexpectedly") },
        text = { Text("A crash report was saved on this device (nothing was sent anywhere). You can find it under More → Crash reports to copy it into a bug report.") },
        confirmButton = { TextButton(onClick = { pending = false; graph.diagnostics.markSeen() }) { Text("OK") } },
    )
}
