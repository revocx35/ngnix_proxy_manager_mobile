package io.github.revocx35.npmmobile

import io.github.revocx35.npmmobile.core.AccessList
import io.github.revocx35.npmmobile.core.AccessListClient
import io.github.revocx35.npmmobile.core.AccessListItem
import io.github.revocx35.npmmobile.core.AuditLogEntry
import io.github.revocx35.npmmobile.core.Certificate
import io.github.revocx35.npmmobile.core.DeadHost
import io.github.revocx35.npmmobile.core.DnsProvider
import io.github.revocx35.npmmobile.core.HostReport
import io.github.revocx35.npmmobile.core.NpmJson
import io.github.revocx35.npmmobile.core.ProxyHost
import io.github.revocx35.npmmobile.core.ProxyLocation
import io.github.revocx35.npmmobile.core.RedirectionHost
import io.github.revocx35.npmmobile.core.Setting
import io.github.revocx35.npmmobile.core.Stream
import io.github.revocx35.npmmobile.core.User
import io.github.revocx35.npmmobile.core.UserPermissions
import io.github.revocx35.npmmobile.core.VersionCheck
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Realistic sample data for UI tests and README screenshots (dates relative to now). */
object Demo {
    private val now = Instant.now()
    private fun iso(i: Instant) = i.toString()
    private fun ago(days: Long) = iso(now.minus(Duration.ofDays(days)))
    private fun inDays(days: Long) = iso(now.plus(Duration.ofDays(days)))
    private val online = JsonObject(mapOf("nginx_online" to JsonPrimitive(true), "nginx_err" to JsonNull))

    val admin = User(1, ago(400), ago(3), false, "admin@example.com", "Alex Morgan", "Alex", "", listOf("admin"), UserPermissions())
    val users = listOf(
        admin,
        User(2, ago(120), ago(10), false, "jane@example.com", "Jane Doe", "Jane", "", emptyList(), UserPermissions(visibility = "user", proxyHosts = "manage", certificates = "view", accessLists = "hidden")),
        User(3, ago(90), ago(30), true, "ci@example.com", "Build Bot", "bot", "", emptyList(), UserPermissions(visibility = "user", proxyHosts = "view")),
    )

    val wildcard = Certificate(1, ago(28), ago(28), 1, "letsencrypt", "", listOf("*.example.com", "example.com"), inDays(62), JsonObject(mapOf("dns_challenge" to JsonPrimitive(true), "dns_provider" to JsonPrimitive("cloudflare"))))
    val nextcloudCert = Certificate(2, ago(42), ago(42), 1, "letsencrypt", "", listOf("nextcloud.example.com"), inDays(48), JsonObject(mapOf("dns_challenge" to JsonPrimitive(false))))
    val vaultCert = Certificate(3, ago(85), ago(85), 1, "letsencrypt", "", listOf("vault.example.com"), inDays(5), JsonObject(mapOf("dns_challenge" to JsonPrimitive(false))))
    val internalCert = Certificate(4, ago(60), ago(60), 1, "other", "Internal CA", listOf("nas.home.lan"), inDays(300))
    val certificates = listOf(wildcard, nextcloudCert, vaultCert, internalCert)

    val family = AccessList(
        1, ago(200), ago(20), 1, "Family", satisfyAny = true, passAuth = false, proxyHostCount = 1,
        items = listOf(AccessListItem(1, "mum", "", "m*******"), AccessListItem(2, "dad", "", "d*********")),
        clients = listOf(AccessListClient(1, "192.168.1.0/24", "allow")),
    )
    val office = AccessList(
        2, ago(150), ago(15), 1, "Office network only", satisfyAny = false, passAuth = false, proxyHostCount = 0,
        clients = listOf(AccessListClient(2, "10.20.0.0/16", "allow"), AccessListClient(3, "all", "deny")),
    )
    val accessLists = listOf(family, office)

    val proxyHosts = listOf(
        ProxyHost(1, ago(300), ago(4), 1, listOf("nextcloud.example.com"), "https", "10.0.0.20", 443, 0, 2, sslForced = true, blockExploits = true, meta = online, allowWebsocketUpgrade = true, http2Support = true, hstsEnabled = true, certificate = nextcloudCert, owner = admin),
        ProxyHost(2, ago(250), ago(12), 1, listOf("jellyfin.example.com", "media.example.com"), "http", "10.0.0.21", 8096, 1, 1, sslForced = true, blockExploits = true, meta = online, allowWebsocketUpgrade = true, http2Support = true, certificate = wildcard, owner = admin, accessList = family),
        ProxyHost(
            3, ago(200), ago(1), 1, listOf("grafana.example.com"), "http", "monitoring", 3000, 0, 1, sslForced = true, meta = online, http2Support = true, certificate = wildcard, owner = admin,
            locations = listOf(ProxyLocation(path = "/api/live", forwardHost = "monitoring", forwardPort = 3000, forwardScheme = "http"), ProxyLocation(path = "/loki", forwardHost = "10.0.0.25", forwardPort = 3100, forwardScheme = "http", accessListId = 2)),
        ),
        ProxyHost(
            4, ago(100), ago(0), 1, listOf("homeassistant.example.com"), "http", "10.0.0.30", 8123, 0, 1, sslForced = true, owner = admin, certificate = wildcard, allowWebsocketUpgrade = true,
            meta = JsonObject(mapOf("nginx_online" to JsonPrimitive(false), "nginx_err" to JsonPrimitive("nginx: [emerg] unknown directive \"proxy_bufer_size\" in /data/nginx/proxy_host/4.conf:48"))),
        ),
        ProxyHost(5, ago(500), ago(90), 1, listOf("old-wiki.example.com"), "http", "10.0.0.40", 80, 0, 0, meta = online, enabled = false, owner = admin),
        ProxyHost(6, ago(85), ago(85), 1, listOf("vault.example.com"), "https", "10.0.0.50", 8200, 0, 3, sslForced = true, blockExploits = true, meta = online, http2Support = true, certificate = vaultCert, owner = admin),
    )
    val redirections = listOf(
        RedirectionHost(1, ago(320), ago(40), 1, listOf("www.example.com"), "example.com", "https", 301, preservePath = true, certificateId = 1, sslForced = true, meta = online, certificate = wildcard),
        RedirectionHost(2, ago(60), ago(60), 1, listOf("blog.example.org"), "example.com/blog", "auto", 302, preservePath = false, meta = online),
    )
    val streams = listOf(
        Stream(1, ago(365), ago(30), 1, 2222, "10.0.0.10", 22, tcpForwarding = true, udpForwarding = false, meta = online),
        Stream(2, ago(20), ago(20), 1, 25565, "10.0.0.60", 25565, tcpForwarding = true, udpForwarding = true, meta = online),
    )
    val deadHosts = listOf(DeadHost(1, ago(30), ago(30), 1, listOf("parked.example.net"), meta = online))

    val audit = listOf(
        AuditLogEntry(9, iso(now.minus(Duration.ofMinutes(12))), null, 1, "proxy-host", 4, "updated", JsonObject(mapOf("domain_names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("homeassistant.example.com"))))), admin),
        AuditLogEntry(8, iso(now.minus(Duration.ofHours(3))), null, 2, "certificate", 3, "renewed", JsonObject(mapOf("domain_names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("vault.example.com"))))), users[1]),
        AuditLogEntry(7, ago(1), null, 1, "stream", 2, "created", JsonObject(mapOf("incoming_port" to JsonPrimitive(25565))), admin),
        AuditLogEntry(6, ago(2), null, 1, "proxy-host", 5, "disabled", JsonObject(mapOf("domain_names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("old-wiki.example.com"))))), admin),
        AuditLogEntry(5, ago(4), null, 1, "access-list", 1, "updated", JsonObject(mapOf("name" to JsonPrimitive("Family"))), admin),
        AuditLogEntry(4, ago(9), null, 1, "user", 3, "created", JsonObject(mapOf("email" to JsonPrimitive("ci@example.com"), "name" to JsonPrimitive("Build Bot"))), admin),
        AuditLogEntry(3, ago(14), null, 1, "redirection-host", 2, "deleted", JsonObject(mapOf("domain_names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("shop.example.org"))))), admin),
    )

    val report = HostReport(proxy = proxyHosts.size, redirection = redirections.size, stream = streams.size, dead = deadHosts.size)
    val settings = listOf(Setting("default-site", "Default Site", "What to show when Nginx is hit with an unknown Host", "congratulations"))
    val dnsProviders = listOf(
        DnsProvider("cloudflare", "Cloudflare", "# Cloudflare API token\ndns_cloudflare_api_token=0123456789abcdef0123456789abcdef01234567"),
        DnsProvider("digitalocean", "DigitalOcean", "dns_digitalocean_token = 0000111122223333444455556666777788889999aaaabbbbccccddddeeeeffff"),
        DnsProvider("route53", "Route 53 (Amazon)", "[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
    )
}

/**
 * An in-memory NPM API on MockWebServer. Sign in with any e-mail and [password]; set [requireTwoFactor]
 * to get a 2FA challenge (code "123456"). Every request is recorded in [requests].
 */
class FakeNpmServer(private val password: String = "demo-pass") : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var requireTwoFactor = false
    private val proxyHosts = CopyOnWriteArrayList(Demo.proxyHosts)

    val url: String get() = server.url("/").toString().removeSuffix("/")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                handle(request)
            } catch (e: Exception) {
                json("""{"error":{"code":500,"message":"fake server: ${e.javaClass.simpleName}"}}""", 500)
            }
        }
        server.start()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private fun <T> list(serializer: kotlinx.serialization.KSerializer<T>, items: List<T>) = json(NpmJson.encodeToString(ListSerializer(serializer), items))
    private fun <T> one(serializer: kotlinx.serialization.KSerializer<T>, item: T) = json(NpmJson.encodeToString(serializer, item))

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath.removePrefix("/api")
        val method = r.method ?: "GET"
        requests += "$method $path"
        if (path != "/" && !path.startsWith("/tokens") && path != "/schema" && r.headers["Authorization"] != "Bearer demo-token") {
            return json("""{"error":{"code":400,"message":"Token has expired"}}""", 400)
        }
        val body = r.body?.utf8().orEmpty()
        return when {
            path == "/" -> json("""{"status":"OK","setup":true,"version":{"major":2,"minor":15,"revision":1}}""")
            path == "/tokens" && method == "POST" -> {
                val secret = NpmJson.parseToJsonElement(body).jsonObject["secret"]?.let { (it as JsonPrimitive).content }
                when {
                    secret != password -> json("""{"error":{"code":400,"message":"Invalid email or password"}}""", 400)
                    requireTwoFactor -> json("""{"requires_2fa":true,"challenge_token":"challenge"}""")
                    else -> token()
                }
            }
            path == "/tokens/2fa" -> if (body.contains("\"123456\"")) token() else json("""{"error":{"code":400,"message":"Invalid verification code"}}""", 400)
            path == "/tokens" -> token()
            path == "/users/me" -> one(User.serializer(), Demo.admin)
            path == "/users" -> list(User.serializer(), Demo.users)
            path.startsWith("/users/") && path.endsWith("/2fa") -> json("""{"enabled":false,"backup_codes_remaining":0}""")
            path.startsWith("/users/") -> one(User.serializer(), Demo.users.first { it.id == path.substringAfter("/users/").substringBefore('/').toInt() })
            path == "/reports/hosts" -> one(HostReport.serializer(), Demo.report.copy(proxy = proxyHosts.size))
            path == "/version/check" -> one(VersionCheck.serializer(), VersionCheck("v2.15.1", "v2.16.0", true))
            path == "/nginx/proxy-hosts" && method == "POST" -> createProxyHost(body)
            path == "/nginx/proxy-hosts" -> list(ProxyHost.serializer(), proxyHosts)
            path.startsWith("/nginx/proxy-hosts/") -> proxyHostAction(method, path, body)
            path == "/nginx/redirection-hosts" -> list(RedirectionHost.serializer(), Demo.redirections)
            path.startsWith("/nginx/redirection-hosts/") -> one(RedirectionHost.serializer(), Demo.redirections.first { it.id == path.substringAfterLast('/').toInt() })
            path == "/nginx/streams" -> list(Stream.serializer(), Demo.streams)
            path.startsWith("/nginx/streams/") -> one(Stream.serializer(), Demo.streams.first { it.id == path.substringAfterLast('/').toInt() })
            path == "/nginx/dead-hosts" -> list(DeadHost.serializer(), Demo.deadHosts)
            path.startsWith("/nginx/dead-hosts/") -> one(DeadHost.serializer(), Demo.deadHosts.first { it.id == path.substringAfterLast('/').toInt() })
            path == "/nginx/certificates/dns-providers" -> list(DnsProvider.serializer(), Demo.dnsProviders)
            path == "/nginx/certificates" -> list(Certificate.serializer(), Demo.certificates)
            path.startsWith("/nginx/certificates/") -> one(Certificate.serializer(), Demo.certificates.first { it.id == path.substringAfterLast('/').toInt() })
            path == "/nginx/access-lists" -> list(AccessList.serializer(), Demo.accessLists)
            path.startsWith("/nginx/access-lists/") -> one(AccessList.serializer(), Demo.accessLists.first { it.id == path.substringAfterLast('/').toInt() })
            path == "/audit-log" -> list(AuditLogEntry.serializer(), Demo.audit)
            path == "/settings" -> list(Setting.serializer(), Demo.settings)
            else -> json("""{"error":{"code":404,"message":"Not Found - $path"}}""", 404)
        }
    }

    private fun token() = json("""{"token":"demo-token","expires":"${Instant.now().plus(Duration.ofDays(1))}"}""")

    private fun createProxyHost(body: String): MockResponse {
        val req = NpmJson.parseToJsonElement(body).jsonObject
        val id = (proxyHosts.maxOfOrNull { it.id } ?: 0) + 1
        val merged = buildJsonObject {
            req.forEach { (k, v) -> if (k != "certificate_id") put(k, v) }
            put("id", id)
            put("certificate_id", 0)
            put("created_on", Instant.now().toString())
            put("meta", buildJsonObject { put("nginx_online", true) })
        }
        val host = NpmJson.decodeFromJsonElement(ProxyHost.serializer(), merged)
        proxyHosts += host
        return json(NpmJson.encodeToString(ProxyHost.serializer(), host), 201)
    }

    private fun proxyHostAction(method: String, path: String, body: String): MockResponse {
        val id = path.removePrefix("/nginx/proxy-hosts/").substringBefore('/').toInt()
        val host = proxyHosts.firstOrNull { it.id == id } ?: return json("""{"error":{"code":404,"message":"Not Found"}}""", 404)
        return when {
            method == "DELETE" -> { proxyHosts.remove(host); json("true") }
            method == "PUT" -> {
                // Like NPM: the body replaces the host's fields (a location sent without access_list_id loses it).
                val merged = buildJsonObject {
                    NpmJson.encodeToJsonElement(ProxyHost.serializer(), host).jsonObject.forEach { (k, v) -> put(k, v) }
                    NpmJson.parseToJsonElement(body).jsonObject.forEach { (k, v) -> if (k != "certificate_id") put(k, v) }
                }
                val updated = NpmJson.decodeFromJsonElement(ProxyHost.serializer(), merged)
                proxyHosts[proxyHosts.indexOf(host)] = updated
                one(ProxyHost.serializer(), updated)
            }
            path.endsWith("/enable") || path.endsWith("/disable") -> {
                proxyHosts[proxyHosts.indexOf(host)] = host.copy(enabled = path.endsWith("/enable"))
                json("true")
            }
            else -> one(ProxyHost.serializer(), host)
        }
    }

    fun hosts(): List<ProxyHost> = proxyHosts.toList()

    override fun close() = server.close()
}
