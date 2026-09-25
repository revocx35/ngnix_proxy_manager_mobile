package io.github.revocx35.npmmobile.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.data.SessionState
import io.github.revocx35.npmmobile.ui.access.AccessListEditor
import io.github.revocx35.npmmobile.ui.access.AccessListsScreen
import io.github.revocx35.npmmobile.ui.audit.AuditLogScreen
import io.github.revocx35.npmmobile.ui.certificates.CertificateDetailScreen
import io.github.revocx35.npmmobile.ui.certificates.CertificatesScreen
import io.github.revocx35.npmmobile.ui.certificates.CustomCertificateScreen
import io.github.revocx35.npmmobile.ui.certificates.NewLetsEncryptScreen
import io.github.revocx35.npmmobile.ui.dashboard.DashboardScreen
import io.github.revocx35.npmmobile.ui.hosts.DeadHostEditor
import io.github.revocx35.npmmobile.ui.hosts.HostsScreen
import io.github.revocx35.npmmobile.ui.hosts.ProxyHostEditor
import io.github.revocx35.npmmobile.ui.hosts.RedirectionHostEditor
import io.github.revocx35.npmmobile.ui.hosts.StreamEditor
import io.github.revocx35.npmmobile.ui.settings.AboutScreen
import io.github.revocx35.npmmobile.ui.settings.AppearanceScreen
import io.github.revocx35.npmmobile.ui.settings.DefaultSiteScreen
import io.github.revocx35.npmmobile.ui.settings.DiagnosticsScreen
import io.github.revocx35.npmmobile.ui.settings.MoreScreen
import io.github.revocx35.npmmobile.ui.settings.ProfileScreen
import io.github.revocx35.npmmobile.ui.settings.ServersScreen
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.users.UserEditor
import io.github.revocx35.npmmobile.ui.users.UsersScreen

/** The signed-in session, available to every screen of the shell. */
val LocalSession = staticCompositionLocalOf<SessionState.SignedIn> { error("No session") }

private data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val matches: (androidx.navigation.NavDestination) -> Boolean)

@Composable
fun MainShell(session: SessionState.SignedIn) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val user = session.user
    val tabs = buildList {
        add(TopLevel("Dashboard", AppIcons.Dashboard, DashboardRoute) { it.hasRoute<DashboardRoute>() })
        add(TopLevel("Hosts", AppIcons.Hosts, HostsRoute()) { it.hasRoute<HostsRoute>() })
        if (user?.canView(Section.Certificates) != false) {
            add(TopLevel("SSL", AppIcons.Certificate, CertificatesRoute) { it.hasRoute<CertificatesRoute>() })
        }
        add(TopLevel("More", AppIcons.More, MoreRoute) { it.hasRoute<MoreRoute>() })
    }
    val onTopLevel = destination != null && tabs.any { t -> destination.hierarchy.any(t.matches) }

    CompositionLocalProvider(LocalSession provides session) {
        Scaffold(
            bottomBar = {
                if (onTopLevel) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = destination.hierarchy.any(tab.matches)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { if (!selected) nav.navigateTopLevel(tab.route) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("tab-${tab.label}"),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = DashboardRoute,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
            ) {
                composable<DashboardRoute> { DashboardScreen(nav) }
                composable<HostsRoute> { HostsScreen(nav, it.toRoute<HostsRoute>().tab) }
                composable<CertificatesRoute> { CertificatesScreen(nav) }
                composable<MoreRoute> { MoreScreen(nav) }

                composable<ProxyHostRoute> { ProxyHostEditor(nav, it.toRoute<ProxyHostRoute>().id) }
                composable<RedirectionHostRoute> { RedirectionHostEditor(nav, it.toRoute<RedirectionHostRoute>().id) }
                composable<StreamRoute> { StreamEditor(nav, it.toRoute<StreamRoute>().id) }
                composable<DeadHostRoute> { DeadHostEditor(nav, it.toRoute<DeadHostRoute>().id) }

                composable<CertificateRoute> { CertificateDetailScreen(nav, it.toRoute<CertificateRoute>().id) }
                composable<NewLetsEncryptRoute> { NewLetsEncryptScreen(nav) }
                composable<CustomCertRoute> { CustomCertificateScreen(nav, it.toRoute<CustomCertRoute>().id) }

                composable<AccessListsRoute> { AccessListsScreen(nav) }
                composable<AccessListRoute> { AccessListEditor(nav, it.toRoute<AccessListRoute>().id) }
                composable<UsersRoute> { UsersScreen(nav) }
                composable<UserRoute> { UserEditor(nav, it.toRoute<UserRoute>().id) }
                composable<AuditLogRoute> { AuditLogScreen(nav) }
                composable<DefaultSiteRoute> { DefaultSiteScreen(nav) }

                composable<ProfileRoute> { ProfileScreen(nav) }
                composable<AppearanceRoute> { AppearanceScreen(nav) }
                composable<ServersRoute> { ServersScreen(nav) }
                composable<DiagnosticsRoute> { DiagnosticsScreen(nav) }
                composable<AboutRoute> { AboutScreen(nav) }
            }
        }
    }
}

/** Bottom-bar navigation: one copy of each tab, state saved and restored when switching. */
fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
