package io.github.revocx35.npmmobile.core

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end test against a real, disposable NPM instance. Skipped unless these are set:
 *   NPM_IT_URL=http://127.0.0.1:18081 NPM_IT_EMAIL=admin@test.local NPM_IT_PASSWORD=...
 * It creates objects named `it-<random>` / on `*.it-test.invalid` domains and deletes them again.
 * Never point it at a production instance (it creates users and changes the default-site setting briefly).
 *
 * NPM_PROBE_URL (optional) only runs the read-only, unauthenticated health/schema probe against that address.
 */
class LiveNpmTest {
    private val url = System.getenv("NPM_IT_URL")
    private val email = System.getenv("NPM_IT_EMAIL")
    private val password = System.getenv("NPM_IT_PASSWORD")
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val tag = "it-" + (100000..999999).random()

    private suspend fun signedIn(): NpmClient {
        val client = NpmClient(ServerAddress.parse(url)!!, http)
        val res = client.login(email, password) as NpmClient.LoginResult.Success
        client.token = res.token.token
        return client
    }

    @Test fun probeHealthAndSchemaReadOnly() = runBlocking {
        val probe = System.getenv("NPM_PROBE_URL")
        assumeTrue("NPM_PROBE_URL not set", !probe.isNullOrBlank())
        val client = NpmClient(ServerAddress.parse(probe)!!, http)
        val health = client.health()
        assertTrue(health.setup)
        println("Probe ${client.server}: NPM ${health.version}")
        // The live schema must still contain every endpoint the app uses (compared with the bundled 2.15.1 copy).
        val live = http.newCall(Request.Builder().url(client.server.resolve("schema")).build()).execute().use { it.body.string() }
        val livePaths = NpmJson.parseToJsonElement(live).let { (it as kotlinx.serialization.json.JsonObject)["paths"] as kotlinx.serialization.json.JsonObject }.keys
        val bundled = NpmJson.parseToJsonElement(javaClass.getResource("/npm-2.15.1-schema.json")!!.readText())
            .let { (it as kotlinx.serialization.json.JsonObject)["paths"] as kotlinx.serialization.json.JsonObject }.keys
        assertTrue("Missing on server: ${bundled - livePaths}", livePaths.containsAll(bundled))
    }

    @Test fun fullRoundTrip() = runBlocking {
        assumeTrue("NPM_IT_URL not set", !url.isNullOrBlank() && !email.isNullOrBlank() && !password.isNullOrBlank())
        val npm = signedIn()
        assertEquals("OK", npm.health().status)
        val me = npm.me()
        assertTrue("test account must be an admin", me.isAdmin)
        val refreshed = npm.refreshToken()
        assertNotNull(refreshed.expiresAt)
        npm.token = refreshed.token

        val cleanup = mutableListOf<suspend () -> Unit>()
        try {
            // Access list
            val acl = npm.createAccessList(
                AccessListRequest(
                    "$tag acl", satisfyAny = true,
                    items = listOf(AccessListItemRequest("alice", "secret-pw")),
                    clients = listOf(AccessListClientRequest("10.0.0.0/8", "allow"), AccessListClientRequest("all", "deny")),
                ),
            )
            cleanup += { npm.deleteAccessList(acl.id) }
            val aclUpdated = npm.updateAccessList(
                acl.id,
                AccessListRequest(
                    "$tag acl2", satisfyAny = false,
                    items = listOf(AccessListItemRequest("alice", "")), // empty password keeps the old one
                    clients = listOf(AccessListClientRequest("192.168.0.0/16", "allow")),
                ),
            )
            assertEquals("$tag acl2", aclUpdated.name)
            val aclFetched = npm.accessList(acl.id)
            assertEquals("alice", aclFetched.items.single().username)
            assertEquals("s" + "*".repeat("secret-pw".length - 1), aclFetched.items.single().hint) // still the old password
            assertEquals("192.168.0.0/16", aclFetched.clients.single().address)

            // Custom certificate (needs openssl to make a throwaway self-signed pair).
            val pem = selfSigned("$tag.it-test.invalid")
            var certId = 0
            if (pem != null) {
                val v = npm.validateCertificate(pem)
                assertEquals("$tag.it-test.invalid", v.certificate?.cn)
                assertTrue(v.certificateKey)
                val cert = npm.createCertificate(CertificateRequest.custom("$tag cert"))
                cleanup += { npm.deleteCertificate(cert.id) }
                npm.uploadCertificate(cert.id, pem)
                val fetched = npm.certificate(cert.id)
                assertEquals(listOf("$tag.it-test.invalid"), fetched.domainNames)
                assertNotNull(fetched.expiresAt)
                certId = cert.id
                // Only Let's Encrypt certificates can be downloaded.
                val e = runCatching { npm.downloadCertificate(cert.id) }.exceptionOrNull()
                assertTrue("$e", e is NpmException.Api && e.status == 400)
            } else {
                println("openssl not found: custom certificate part skipped")
            }
            assertTrue(npm.dnsProviders().any { it.id == "cloudflare" })

            // Proxy host
            val domain = "app.$tag.it-test.invalid"
            val proxy = npm.createProxyHost(
                ProxyHostRequest(
                    listOf(domain), "http", "10.9.9.9", 8080, certificateId = CertificateRef.of(certId), sslForced = certId > 0,
                    http2Support = true, blockExploits = true, allowWebsocketUpgrade = true, accessListId = acl.id,
                    locations = listOf(LocationRequest("/api", "http", "10.9.9.10/v1", 9000)), advancedConfig = "# $tag",
                ),
            )
            cleanup += { npm.deleteHost(HostKind.Proxy, proxy.id) }
            assertEquals(listOf(domain), proxy.domainNames)
            val proxyFetched = npm.proxyHost(proxy.id)
            assertTrue("nginx config failed: ${proxyFetched.nginxError}", proxyFetched.nginxOnline)
            assertEquals(acl.id, proxyFetched.accessList?.id)
            assertEquals("/api", proxyFetched.locations.single().path)
            val updated = npm.updateProxyHost(
                proxy.id,
                ProxyHostRequest(listOf(domain, "www.$domain"), "https", "10.9.9.8", 8443, certificateId = CertificateRef.None, cachingEnabled = true),
            )
            assertEquals(2, updated.domainNames.size)
            assertEquals(0, updated.certificateId)
            assertEquals(0, updated.accessListId)
            npm.disableHost(HostKind.Proxy, proxy.id)
            assertFalse(npm.proxyHost(proxy.id).enabled)
            npm.enableHost(HostKind.Proxy, proxy.id)
            assertTrue(npm.proxyHost(proxy.id).enabled)
            assertTrue(npm.proxyHosts().any { it.id == proxy.id })
            val dup = runCatching { npm.createProxyHost(ProxyHostRequest(listOf(domain), "http", "x", 1)) }.exceptionOrNull()
            assertTrue("$dup", dup is NpmException.Api && dup.message!!.contains("already in use"))

            // Redirection, 404 host, stream
            val redir = npm.createRedirectionHost(RedirectionHostRequest(listOf("old.$tag.it-test.invalid"), 302, "https", "new.example.com", preservePath = true))
            cleanup += { npm.deleteHost(HostKind.Redirection, redir.id) }
            assertEquals(302, npm.updateRedirectionHost(redir.id, RedirectionHostRequest(listOf("old.$tag.it-test.invalid"), 302, "auto", "new2.example.com")).forwardHttpCode)
            assertEquals("new2.example.com", npm.redirectionHost(redir.id).forwardDomainName)

            val dead = npm.createDeadHost(DeadHostRequest(listOf("gone.$tag.it-test.invalid")))
            cleanup += { npm.deleteHost(HostKind.Dead, dead.id) }
            npm.disableHost(HostKind.Dead, dead.id)
            assertFalse(npm.deadHosts().first { it.id == dead.id }.enabled)

            val port = (40000..49999).random()
            val stream = npm.createStream(StreamRequest(port, "10.9.9.11", 22, tcpForwarding = true, udpForwarding = true))
            cleanup += { npm.deleteHost(HostKind.Stream, stream.id) }
            assertEquals(port + 1, npm.updateStream(stream.id, StreamRequest(port + 1, "10.9.9.12", 2222)).incomingPort)
            assertEquals("10.9.9.12", npm.stream(stream.id).forwardingHost)

            val report = npm.hostReport()
            assertTrue(report.proxy >= 1 && report.redirection >= 1 && report.dead >= 1 && report.stream >= 1)

            // Users, permissions, password, 2FA
            val userEmail = "$tag@it-test.invalid"
            val user = npm.createUser(UserRequest("It Tester", "tester", userEmail, auth = UserRequest.passwordAuth("first-pass-1")))
            cleanup += { npm.deleteUser(user.id) }
            npm.setPermissions(user.id, UserPermissions(visibility = "user", proxyHosts = "view", certificates = "hidden"))
            assertEquals("hidden", npm.user(user.id).permissions?.certificates)
            npm.changePassword(user.id, "second-pass-2", current = null)
            val asUser = NpmClient(npm.server, http)
            val login = asUser.login(userEmail, "second-pass-2") as NpmClient.LoginResult.Success
            asUser.token = login.token.token
            val self = asUser.me()
            assertFalse(self.isAdmin)
            assertFalse(self.canManage(Section.ProxyHosts))
            val forbidden = runCatching { asUser.createProxyHost(ProxyHostRequest(listOf("x.$tag.it-test.invalid"), "http", "x", 1)) }.exceptionOrNull()
            // NPM 2.15.1 bug: lib/access.js throws `errs.PermissionError(...)` without `new`, so a denied write is
            // a 500 "Internal Error" instead of a 403. The app hides actions the user may not perform instead.
            assertTrue("$forbidden", forbidden is NpmException.Forbidden || (forbidden is NpmException.Api && forbidden.status == 500))
            assertTrue(asUser.proxyHosts().isEmpty()) // visibility=user: sees only own hosts

            val setup = asUser.startTwoFactorSetup(user.id)
            assertTrue(setup.otpauthUrl.startsWith("otpauth://"))
            val codes = asUser.enableTwoFactor(user.id, totp(setup.secret))
            assertTrue(codes.backupCodes.size >= 4)
            assertTrue(asUser.twoFactorStatus(user.id).enabled)
            val challenge = NpmClient(npm.server, http).login(userEmail, "second-pass-2")
            assertTrue(challenge is NpmClient.LoginResult.TwoFactorRequired)
            val second = NpmClient(npm.server, http).loginWith2fa((challenge as NpmClient.LoginResult.TwoFactorRequired).challengeToken, codes.backupCodes.first())
            assertTrue(second.token.isNotEmpty())
            asUser.disableTwoFactor(user.id, totpFresh(setup.secret))
            assertFalse(asUser.twoFactorStatus(user.id).enabled)
            assertTrue(npm.users().any { it.email == userEmail })

            // Settings (restored afterwards)
            val site = npm.settings().first { it.id == Setting.DEFAULT_SITE }
            npm.updateSetting(Setting.DEFAULT_SITE, SettingRequest.defaultSite("redirect", redirect = "https://example.com"))
            cleanup += { npm.updateSetting(Setting.DEFAULT_SITE, SettingRequest(site.value, site.meta)) }
            assertEquals("redirect", npm.settings().first { it.id == Setting.DEFAULT_SITE }.value)

            // Audit log sees what we did.
            assertTrue(npm.auditLog().any { it.objectType == "proxy-host" && it.objectId == proxy.id })
            println("Live round trip OK against ${npm.server} as ${me.email}")
        } finally {
            for (step in cleanup.asReversed()) runCatching { step() }.onFailure { println("cleanup: $it") }
        }
    }

    private fun selfSigned(cn: String): CertificateFiles? {
        val dir = File.createTempFile("npm-it", "").apply { delete(); mkdirs() }
        return try {
            val p = ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30",
                "-keyout", "key.pem", "-out", "cert.pem", "-subj", "/CN=$cn", "-addext", "subjectAltName=DNS:$cn",
            ).directory(dir).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (p.waitFor() != 0) return null
            CertificateFiles(File(dir, "cert.pem").readBytes(), File(dir, "key.pem").readBytes())
        } catch (_: Exception) {
            null
        } finally {
            dir.deleteRecursively()
        }
    }

    /** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), as used by NPM. */
    private fun totp(secret: String, at: Long = System.currentTimeMillis() / 1000): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = secret.uppercase().filter { it in alphabet }.map { alphabet.indexOf(it).toString(2).padStart(5, '0') }.joinToString("")
        val key = bits.chunked(8).filter { it.length == 8 }.map { it.toInt(2).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(at / 30).array())
        val o = h.last().toInt() and 0xf
        val n = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    /** A code from the next time step: NPM may refuse to accept the same TOTP code twice. */
    private fun totpFresh(secret: String): String {
        val now = System.currentTimeMillis() / 1000
        Thread.sleep(((30 - now % 30) + 1) * 1000)
        return totp(secret)
    }
}
