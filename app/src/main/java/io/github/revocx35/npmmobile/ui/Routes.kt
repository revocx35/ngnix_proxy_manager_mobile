package io.github.revocx35.npmmobile.ui

import kotlinx.serialization.Serializable

// Type-safe navigation routes. An `id` of 0 means "create a new one".

@Serializable data object DashboardRoute
@Serializable data class HostsRoute(val tab: Int = 0)
@Serializable data object CertificatesRoute
@Serializable data object MoreRoute

@Serializable data class ProxyHostRoute(val id: Int = 0)
@Serializable data class RedirectionHostRoute(val id: Int = 0)
@Serializable data class StreamRoute(val id: Int = 0)
@Serializable data class DeadHostRoute(val id: Int = 0)

@Serializable data class CertificateRoute(val id: Int)
@Serializable data object NewLetsEncryptRoute
@Serializable data class CustomCertRoute(val id: Int = 0)

@Serializable data object AccessListsRoute
@Serializable data class AccessListRoute(val id: Int = 0)
@Serializable data object UsersRoute
@Serializable data class UserRoute(val id: Int = 0)
@Serializable data object AuditLogRoute
@Serializable data object DefaultSiteRoute

@Serializable data object ProfileRoute
@Serializable data object AppearanceRoute
@Serializable data object ServersRoute
@Serializable data object DiagnosticsRoute
@Serializable data object AboutRoute
