package io.github.revocx35.npmmobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

// Response models of the Nginx Proxy Manager API (checked against NPM 2.15.1, `GET /api/schema`).
// Property names are camelCase; NpmJson maps them to NPM's snake_case keys.
// Every field has a default so a missing key never breaks parsing.

@Serializable
data class HealthStatus(
    val status: String = "",
    val setup: NBool = true,
    val version: NpmVersion? = null,
)

@Serializable
data class NpmVersion(val major: Int = 0, val minor: Int = 0, val revision: Int = 0) : Comparable<NpmVersion> {
    override fun toString() = "$major.$minor.$revision"
    override fun compareTo(other: NpmVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.revision })
}

/** `POST /tokens` answers with either a token or a 2FA challenge. */
@Serializable
data class TokenResponse(
    val token: String? = null,
    val expires: String? = null,
    @SerialName("requires_2fa") val requires2fa: NBool = false,
    val challengeToken: String? = null,
)

data class AuthToken(val token: String, val expiresAt: Instant?)

@Serializable
data class UserPermissions(
    val visibility: String = "all",
    val proxyHosts: String = "manage",
    val redirectionHosts: String = "manage",
    val deadHosts: String = "manage",
    val streams: String = "manage",
    val accessLists: String = "manage",
    val certificates: String = "manage",
) {
    fun level(section: Section): String = when (section) {
        Section.ProxyHosts -> proxyHosts
        Section.RedirectionHosts -> redirectionHosts
        Section.DeadHosts -> deadHosts
        Section.Streams -> streams
        Section.AccessLists -> accessLists
        Section.Certificates -> certificates
    }

    companion object {
        const val HIDDEN = "hidden"
        const val VIEW = "view"
        const val MANAGE = "manage"
        val LEVELS = listOf(HIDDEN, VIEW, MANAGE)
    }
}

/** Permission-controlled sections of NPM. */
enum class Section { ProxyHosts, RedirectionHosts, DeadHosts, Streams, AccessLists, Certificates }

@Serializable
data class User(
    val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val isDisabled: NBool = false,
    val email: String = "",
    val name: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val roles: List<String> = emptyList(),
    val permissions: UserPermissions? = null,
) {
    val isAdmin: Boolean get() = "admin" in roles
    val displayName: String get() = name.ifBlank { nickname.ifBlank { email } }

    /** Admins can do everything; other users follow their permission levels. */
    fun canView(section: Section) = isAdmin || (permissions ?: UserPermissions()).level(section) != UserPermissions.HIDDEN
    fun canManage(section: Section) = isAdmin || (permissions ?: UserPermissions()).level(section) == UserPermissions.MANAGE
}

@Serializable
data class HostReport(val proxy: Int = 0, val redirection: Int = 0, val stream: Int = 0, val dead: Int = 0)

@Serializable
data class VersionCheck(val current: String? = null, val latest: String? = null, val updateAvailable: NBool = false)

/** Common view of the four host kinds for lists, status and enable/disable/delete. */
sealed interface NpmHost {
    val id: Int
    val enabled: Boolean
    val meta: JsonObject
    val kind: HostKind
    val title: String
    val certificateId: Int

    /** NPM writes `meta.nginx_online=false` + `meta.nginx_err` when the generated nginx config failed. */
    val nginxOnline: Boolean get() = (meta["nginx_online"] as? JsonPrimitive)?.booleanOrNull ?: true
    val nginxError: String? get() = (meta["nginx_err"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    val status: HostStatus
        get() = when {
            !enabled -> HostStatus.Disabled
            !nginxOnline -> HostStatus.Offline
            else -> HostStatus.Online
        }
}

enum class HostStatus { Online, Offline, Disabled }

enum class HostKind(val path: String, val section: Section, val auditType: String) {
    Proxy("nginx/proxy-hosts", Section.ProxyHosts, "proxy-host"),
    Redirection("nginx/redirection-hosts", Section.RedirectionHosts, "redirection-host"),
    Stream("nginx/streams", Section.Streams, "stream"),
    Dead("nginx/dead-hosts", Section.DeadHosts, "dead-host"),
}

@Serializable
data class ProxyLocation(
    val id: Int? = null,
    val path: String = "",
    val forwardScheme: String = "http",
    val forwardHost: String = "",
    val forwardPort: Int = 80,
    val forwardPath: String? = null,
    val advancedConfig: String? = null,
)

@Serializable
data class ProxyHost(
    override val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val ownerUserId: Int = 0,
    val domainNames: List<String> = emptyList(),
    val forwardScheme: String = "http",
    val forwardHost: String = "",
    val forwardPort: Int = 80,
    val accessListId: Int = 0,
    override val certificateId: Int = 0,
    val sslForced: NBool = false,
    val cachingEnabled: NBool = false,
    val blockExploits: NBool = false,
    val advancedConfig: String = "",
    override val meta: JsonObject = JsonObject(emptyMap()),
    val allowWebsocketUpgrade: NBool = false,
    val http2Support: NBool = false,
    override val enabled: NBool = true,
    val locations: List<ProxyLocation> = emptyList(),
    val hstsEnabled: NBool = false,
    val hstsSubdomains: NBool = false,
    val trustForwardedProto: NBool = false,
    val certificate: Certificate? = null,
    val owner: User? = null,
    val accessList: AccessList? = null,
) : NpmHost {
    override val kind get() = HostKind.Proxy
    override val title get() = domainNames.joinToString(", ")
    val target: String get() = "$forwardScheme://$forwardHost:$forwardPort"
}

@Serializable
data class RedirectionHost(
    override val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val ownerUserId: Int = 0,
    val domainNames: List<String> = emptyList(),
    val forwardDomainName: String = "",
    val forwardScheme: String = "auto",
    val forwardHttpCode: Int = 301,
    val preservePath: NBool = false,
    override val certificateId: Int = 0,
    val sslForced: NBool = false,
    val blockExploits: NBool = false,
    val advancedConfig: String = "",
    override val meta: JsonObject = JsonObject(emptyMap()),
    val http2Support: NBool = false,
    override val enabled: NBool = true,
    val hstsEnabled: NBool = false,
    val hstsSubdomains: NBool = false,
    val certificate: Certificate? = null,
    val owner: User? = null,
) : NpmHost {
    override val kind get() = HostKind.Redirection
    override val title get() = domainNames.joinToString(", ")
    val target: String
        get() = (if (forwardScheme == "auto") "" else "$forwardScheme://") + forwardDomainName
}

@Serializable
data class DeadHost(
    override val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val ownerUserId: Int = 0,
    val domainNames: List<String> = emptyList(),
    override val certificateId: Int = 0,
    val sslForced: NBool = false,
    val advancedConfig: String = "",
    override val meta: JsonObject = JsonObject(emptyMap()),
    val http2Support: NBool = false,
    override val enabled: NBool = true,
    val hstsEnabled: NBool = false,
    val hstsSubdomains: NBool = false,
    val certificate: Certificate? = null,
    val owner: User? = null,
) : NpmHost {
    override val kind get() = HostKind.Dead
    override val title get() = domainNames.joinToString(", ")
}

@Serializable
data class Stream(
    override val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val ownerUserId: Int = 0,
    val incomingPort: Int = 0,
    val forwardingHost: String = "",
    val forwardingPort: Int = 0,
    val tcpForwarding: NBool = true,
    val udpForwarding: NBool = false,
    override val meta: JsonObject = JsonObject(emptyMap()),
    override val enabled: NBool = true,
    override val certificateId: Int = 0,
    val certificate: Certificate? = null,
    val owner: User? = null,
) : NpmHost {
    override val kind get() = HostKind.Stream
    override val title get() = "Port $incomingPort"
    val protocols: String
        get() = listOfNotNull("TCP".takeIf { tcpForwarding }, "UDP".takeIf { udpForwarding }).joinToString(" + ").ifEmpty { "—" }
    val target: String get() = "$forwardingHost:$forwardingPort"
}

@Serializable
data class AccessListItem(
    val id: Int? = null,
    val username: String = "",
    val password: String = "",
    /** Masked password from NPM ("s******"); the real password is never returned. */
    val hint: String? = null,
)

@Serializable
data class AccessListClient(
    val id: Int? = null,
    val address: String = "",
    val directive: String = "allow",
)

@Serializable
data class AccessList(
    val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val ownerUserId: Int = 0,
    val name: String = "",
    val meta: JsonObject = JsonObject(emptyMap()),
    val satisfyAny: NBool = false,
    val passAuth: NBool = false,
    val proxyHostCount: Int = 0,
    val owner: User? = null,
    val items: List<AccessListItem> = emptyList(),
    val clients: List<AccessListClient> = emptyList(),
)

@Serializable
data class Certificate(
    val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val ownerUserId: Int = 0,
    val provider: String = "",
    val niceName: String = "",
    val domainNames: List<String> = emptyList(),
    val expiresOn: String? = null,
    val meta: JsonObject = JsonObject(emptyMap()),
    val owner: User? = null,
) {
    val isLetsEncrypt: Boolean get() = provider == PROVIDER_LETSENCRYPT
    val displayName: String get() = niceName.ifBlank { domainNames.joinToString(", ") }.ifBlank { "Certificate #$id" }
    val expiresAt: Instant? get() = NpmDates.parse(expiresOn)
    val usesDnsChallenge: Boolean get() = (meta["dns_challenge"] as? JsonPrimitive)?.booleanOrNull ?: false
    val dnsProvider: String? get() = (meta["dns_provider"] as? JsonPrimitive)?.contentOrNull

    /** A custom certificate that was created but whose files were never uploaded has no domains. */
    val isIncomplete: Boolean get() = !isLetsEncrypt && domainNames.isEmpty()

    companion object {
        const val PROVIDER_LETSENCRYPT = "letsencrypt"
        const val PROVIDER_OTHER = "other"
    }
}

@Serializable
data class DnsProvider(val id: String = "", val name: String = "", val credentials: String = "")

@Serializable
data class CertificateValidation(val certificate: CertificateInfo? = null, val certificateKey: NBool = false)

@Serializable
data class CertificateInfo(val cn: String = "", val issuer: String = "", val dates: CertificateDates? = null)

@Serializable
data class CertificateDates(val from: Long = 0, val to: Long = 0)

@Serializable
data class Setting(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val value: String = "",
    val meta: JsonObject = JsonObject(emptyMap()),
) {
    fun metaString(key: String): String = (meta[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val DEFAULT_SITE = "default-site"
        val DEFAULT_SITE_VALUES = listOf("congratulations", "404", "444", "redirect", "html")
    }
}

@Serializable
data class AuditLogEntry(
    val id: Int = 0,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
    val userId: Int = 0,
    val objectType: String = "",
    val objectId: Int = 0,
    val action: String = "",
    val meta: JsonObject = JsonObject(emptyMap()),
    val user: User? = null,
) {
    /** Best-effort human label for the object the entry is about (domains, name, port…). */
    val objectLabel: String
        get() {
            (meta["domain_names"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
                val names = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (names.isNotEmpty()) return names.joinToString(", ")
            }
            for (key in listOf("nice_name", "name", "email")) {
                (meta[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            }
            (meta["incoming_port"] as? JsonPrimitive)?.intOrNull?.let { return "Port $it" }
            return "#$objectId"
        }
}

@Serializable
data class TwoFactorStatus(val enabled: NBool = false, val backupCodesRemaining: Int = 0)

@Serializable
data class TwoFactorSetup(val secret: String = "", val otpauthUrl: String = "")

@Serializable
data class BackupCodes(val backupCodes: List<String> = emptyList())

/** Extracts a string from a JSON meta object, or null. */
fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
