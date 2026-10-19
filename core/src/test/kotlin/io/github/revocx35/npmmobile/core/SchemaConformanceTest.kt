package io.github.revocx35.npmmobile.core

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NPM rejects request bodies with keys its JSON schema doesn't list (`additionalProperties: false`).
 * This encodes a fully populated instance of every request class and checks each key (recursively) against
 * the OpenAPI schemas served by every supported NPM release (`GET /api/schema`, saved in src/test/resources).
 * Add the schema of a newer NPM to [VERSIONS] and this test will point at anything that changed.
 */
class SchemaConformanceTest {
    private companion object {
        val VERSIONS = listOf("2.15.1", "2.16.0")
    }

    private val schemas = VERSIONS.associateWith {
        NpmJson.parseToJsonElement(javaClass.getResource("/npm-$it-schema.json")!!.readText()).jsonObject
    }

    private fun bodySchema(schema: JsonObject, path: String, method: String): JsonObject {
        val op = schema["paths"]!!.jsonObject[path]!!.jsonObject[method]!!.jsonObject
        val content = op["requestBody"]!!.jsonObject["content"]!!.jsonObject
        return (content["application/json"] ?: content.values.first()).jsonObject["schema"]!!.jsonObject
    }

    private fun <T> check(
        path: String, method: String, serializer: SerializationStrategy<T>, value: T, requireAll: Boolean = true, versions: List<String> = VERSIONS,
    ) {
        val encoded = NpmJson.encodeToJsonElement(serializer, value)
        val problems = mutableListOf<String>()
        versions.forEach { compare(encoded, bodySchema(schemas.getValue(it), path, method), "NPM $it: $method $path", problems, requireAll) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun compare(value: JsonElement, sch: JsonObject, where: String, problems: MutableList<String>, requireAll: Boolean) {
        val type = sch["type"]?.let { if (it is JsonArray) it.map { t -> t.jsonPrimitive.content } else listOf(it.jsonPrimitive.content) }
        when (value) {
            is JsonObject -> {
                val props = sch["properties"]?.jsonObject ?: return // free-form object (e.g. meta)
                val strict = sch["additionalProperties"]?.jsonPrimitive?.content == "false"
                for ((k, v) in value) {
                    val p = props[k]
                    if (p == null) {
                        if (strict) problems += "$where: key '$k' is not allowed by the schema"
                    } else {
                        compare(v, p.jsonObject, "$where.$k", problems, requireAll)
                    }
                }
                if (requireAll) {
                    sch["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it !in value }?.forEach {
                        problems += "$where: required key '$it' is missing"
                    }
                }
            }
            is JsonArray -> {
                if (type != null && "array" !in type) problems += "$where: schema type is $type, got array"
                val items = sch["items"]?.jsonObject ?: return
                value.forEach { compare(it, items, "$where[]", problems, requireAll) }
            }
            else -> {
                if (type != null && "object" in type && "string" !in type) problems += "$where: expected an object"
                val enum = sch["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
                val prim = value.jsonPrimitive
                if (enum != null && prim.content !in enum) problems += "$where: '${prim.content}' not in $enum"
                if (type != null && prim.isString && type.none { it == "string" } && sch["anyOf"] == null) {
                    problems += "$where: schema type is $type, got a string"
                }
                if (type != null && !prim.isString && "boolean" in type && prim.content !in setOf("true", "false")) {
                    problems += "$where: expected boolean"
                }
            }
        }
    }

    private val le = LetsEncryptOptions(dnsChallenge = true, dnsProvider = "cloudflare", dnsProviderCredentials = "a = b", propagationSeconds = 30)

    @Test fun proxyHost() {
        val body = ProxyHostRequest(
            domainNames = listOf("a.example.com"), forwardScheme = "https", forwardHost = "10.0.0.1", forwardPort = 443,
            certificateId = CertificateRef.RequestNew, sslForced = true, hstsEnabled = true, hstsSubdomains = true,
            trustForwardedProto = true, http2Support = true, blockExploits = true, cachingEnabled = true,
            allowWebsocketUpgrade = true, accessListId = 2, advancedConfig = "# x",
            locations = listOf(LocationRequest("/api", "http", "10.0.0.2/sub", 8080, "# y")), meta = le.toMeta(),
        )
        check("/nginx/proxy-hosts", "post", ProxyHostRequest.serializer(), body)
        check("/nginx/proxy-hosts/{hostID}", "put", ProxyHostRequest.serializer(), body.copy(certificateId = CertificateRef.Existing(3), meta = null))

        // Per-location access lists only exist since 2.16 (2.15 rejects the key, so it is left out when null).
        val withAcl = body.copy(locations = listOf(LocationRequest("/admin", "http", "10.0.0.3", 81, accessListId = 4)), meta = null)
        check("/nginx/proxy-hosts/{hostID}", "put", ProxyHostRequest.serializer(), withAcl, versions = listOf("2.16.0"))
        assertTrue("access_list_id" !in NpmJson.encodeToString(LocationRequest.serializer(), LocationRequest("/a", "http", "h", 1)))
    }

    @Test fun locationAccessListsNeedNpm216() {
        assertTrue("access_list_id" in schemas.getValue("2.16.0").toString())
        assertTrue(NpmVersion.parse("2.16.0")!! >= NpmVersion.LOCATION_ACCESS_LISTS)
        assertTrue(NpmVersion.parse("v2.17")!! >= NpmVersion.LOCATION_ACCESS_LISTS)
        assertTrue(NpmVersion.parse("2.15.1")!! < NpmVersion.LOCATION_ACCESS_LISTS)
        assertTrue(NpmVersion.parse(null) == null && NpmVersion.parse("dev") == null && NpmVersion.parse("") == null)
    }

    @Test fun redirectionDeadStream() {
        val r = RedirectionHostRequest(
            listOf("old.example.com"), 302, "https", "new.example.com/path", preservePath = true,
            certificateId = CertificateRef.Existing(1), sslForced = true, hstsEnabled = true, hstsSubdomains = true,
            http2Support = true, blockExploits = true, advancedConfig = "#", meta = le.toMeta(),
        )
        check("/nginx/redirection-hosts", "post", RedirectionHostRequest.serializer(), r)
        check("/nginx/redirection-hosts/{hostID}", "put", RedirectionHostRequest.serializer(), r)

        val d = DeadHostRequest(listOf("gone.example.com"), CertificateRef.RequestNew, true, true, true, true, "#", le.toMeta())
        check("/nginx/dead-hosts", "post", DeadHostRequest.serializer(), d)
        check("/nginx/dead-hosts/{hostID}", "put", DeadHostRequest.serializer(), d)

        val s = StreamRequest(2222, "10.0.0.7", 22, tcpForwarding = true, udpForwarding = true, certificateId = CertificateRef.Existing(4))
        check("/nginx/streams", "post", StreamRequest.serializer(), s)
        check("/nginx/streams/{streamID}", "put", StreamRequest.serializer(), s)
    }

    @Test fun accessListCertificateUserSettings() {
        val a = AccessListRequest(
            "Office", satisfyAny = true, passAuth = true,
            items = listOf(AccessListItemRequest("bob", "pw")), clients = listOf(AccessListClientRequest("10.0.0.0/8", "allow")),
        )
        check("/nginx/access-lists", "post", AccessListRequest.serializer(), a)
        check("/nginx/access-lists/{listID}", "put", AccessListRequest.serializer(), a)

        check("/nginx/certificates", "post", CertificateRequest.serializer(), CertificateRequest.letsEncrypt(listOf("a.example.com"), le))
        check("/nginx/certificates", "post", CertificateRequest.serializer(), CertificateRequest.custom("Mine"))
        check("/nginx/certificates/test-http", "post", TestHttpRequest.serializer(), TestHttpRequest(listOf("a.example.com")))

        val u = UserRequest("Jane Doe", "Jane", "jane@example.com", listOf("admin"), isDisabled = true, auth = UserRequest.passwordAuth("secret123"))
        check("/users", "post", UserRequest.serializer(), u)
        check("/users/{userID}", "put", UserRequest.serializer(), u.copy(auth = null))
        check("/users/{userID}/permissions", "put", UserPermissions.serializer(), UserPermissions(visibility = "user", certificates = "view"))
        check("/users/{userID}/auth", "put", PasswordChangeRequest.serializer(), PasswordChangeRequest("newsecret", "old"))
        check("/users/{userID}/2fa/enable", "post", TwoFactorCodeRequest.serializer(), TwoFactorCodeRequest("123456"))

        check("/settings/{settingID}", "put", SettingRequest.serializer(), SettingRequest.defaultSite("html", html = "<b>hi</b>"))
        check("/tokens", "post", LoginRequest.serializer(), LoginRequest("a@b.c", "pw"))
        check("/tokens/2fa", "post", TwoFactorLoginRequest.serializer(), TwoFactorLoginRequest("challenge", "123456"))
    }
}
