package io.github.revocx35.npmmobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Request bodies. NPM validates them against its JSON schema with `additionalProperties: false`, so every
// class here must only contain keys the schema allows (SchemaConformanceTest checks this against the real
// schema). `null` fields are left out of the JSON (NpmJson.explicitNulls = false).

@Serializable
data class LoginRequest(val identity: String, val secret: String, val scope: String = "user")

@Serializable
data class TwoFactorLoginRequest(val challengeToken: String, val code: String)

/**
 * Options for a Let's Encrypt certificate: used by `POST /nginx/certificates` and, as host `meta`,
 * when a host is saved with `certificate_id = "new"`. The account e-mail is taken from the NPM user.
 */
@Serializable
data class LetsEncryptOptions(
    val dnsChallenge: Boolean = false,
    val dnsProvider: String? = null,
    val dnsProviderCredentials: String? = null,
    val propagationSeconds: Int? = null,
    val keyType: String = KEY_ECDSA,
) {
    fun toMeta(): JsonObject = NpmJson.encodeToJsonElement(serializer(), this) as JsonObject

    companion object {
        const val KEY_ECDSA = "ecdsa"
        const val KEY_RSA = "rsa"
    }
}

@Serializable
data class ProxyHostRequest(
    val domainNames: List<String>,
    val forwardScheme: String,
    val forwardHost: String,
    val forwardPort: Int,
    val certificateId: CertificateRef = CertificateRef.None,
    val sslForced: Boolean = false,
    val hstsEnabled: Boolean = false,
    val hstsSubdomains: Boolean = false,
    val trustForwardedProto: Boolean = false,
    val http2Support: Boolean = false,
    val blockExploits: Boolean = false,
    val cachingEnabled: Boolean = false,
    val allowWebsocketUpgrade: Boolean = false,
    val accessListId: Int = 0,
    val advancedConfig: String = "",
    val locations: List<LocationRequest> = emptyList(),
    /** Only sent with [CertificateRef.RequestNew]: the Let's Encrypt options for the new certificate. */
    val meta: JsonObject? = null,
)

@Serializable
data class LocationRequest(
    val path: String,
    val forwardScheme: String,
    /** May contain a path ("10.0.0.5/app"); NPM splits it into host and `forward_path` itself. */
    val forwardHost: String,
    val forwardPort: Int,
    val advancedConfig: String = "",
    /**
     * Per-path access list, NPM 2.16+ only. Left out when null: 2.15 rejects the key, and 2.16 drops a location's
     * access list when the key is missing, so a loaded value must always be sent back.
     */
    val accessListId: Int? = null,
)

@Serializable
data class RedirectionHostRequest(
    val domainNames: List<String>,
    val forwardHttpCode: Int,
    val forwardScheme: String,
    val forwardDomainName: String,
    val preservePath: Boolean = false,
    val certificateId: CertificateRef = CertificateRef.None,
    val sslForced: Boolean = false,
    val hstsEnabled: Boolean = false,
    val hstsSubdomains: Boolean = false,
    val http2Support: Boolean = false,
    val blockExploits: Boolean = false,
    val advancedConfig: String = "",
    val meta: JsonObject? = null,
)

@Serializable
data class DeadHostRequest(
    val domainNames: List<String>,
    val certificateId: CertificateRef = CertificateRef.None,
    val sslForced: Boolean = false,
    val hstsEnabled: Boolean = false,
    val hstsSubdomains: Boolean = false,
    val http2Support: Boolean = false,
    val advancedConfig: String = "",
    val meta: JsonObject? = null,
)

@Serializable
data class StreamRequest(
    val incomingPort: Int,
    val forwardingHost: String,
    val forwardingPort: Int,
    val tcpForwarding: Boolean = true,
    val udpForwarding: Boolean = false,
    val certificateId: CertificateRef = CertificateRef.None,
    val meta: JsonObject? = null,
)

@Serializable
data class AccessListRequest(
    val name: String,
    val satisfyAny: Boolean = false,
    val passAuth: Boolean = false,
    /** An empty password keeps the user's current password (NPM behaviour). */
    val items: List<AccessListItemRequest> = emptyList(),
    val clients: List<AccessListClientRequest> = emptyList(),
)

@Serializable
data class AccessListItemRequest(val username: String, val password: String)

@Serializable
data class AccessListClientRequest(val address: String, val directive: String)

@Serializable
data class CertificateRequest(
    val provider: String,
    val niceName: String? = null,
    val domainNames: List<String>? = null,
    val meta: JsonObject? = null,
) {
    companion object {
        fun letsEncrypt(domains: List<String>, options: LetsEncryptOptions) =
            CertificateRequest(provider = Certificate.PROVIDER_LETSENCRYPT, domainNames = domains, meta = options.toMeta())

        fun custom(niceName: String) = CertificateRequest(provider = Certificate.PROVIDER_OTHER, niceName = niceName)
    }
}

@Serializable
data class TestHttpRequest(val domains: List<String>)

/** PEM files for a custom certificate (sent as multipart form data). */
data class CertificateFiles(
    val certificate: ByteArray,
    val certificateKey: ByteArray,
    val intermediateCertificate: ByteArray? = null,
)

@Serializable
data class UserRequest(
    val name: String,
    val nickname: String,
    val email: String,
    val roles: List<String> = emptyList(),
    val isDisabled: Boolean = false,
    /** Only when creating a user: `{"type":"password","secret":"…"}`. */
    val auth: JsonObject? = null,
) {
    companion object {
        fun passwordAuth(secret: String) = buildJsonObject {
            put("type", "password")
            put("secret", secret)
        }
    }
}

@Serializable
data class PasswordChangeRequest(val secret: String, val current: String? = null, val type: String = "password")

@Serializable
data class TwoFactorCodeRequest(val code: String)

@Serializable
data class SettingRequest(val value: String, val meta: JsonObject = JsonObject(emptyMap())) {
    companion object {
        fun defaultSite(value: String, redirect: String = "", html: String = "") = SettingRequest(
            value = value,
            meta = JsonObject(
                buildMap {
                    if (value == "redirect") put("redirect", JsonPrimitive(redirect))
                    if (value == "html") put("html", JsonPrimitive(html))
                },
            ),
        )
    }
}
