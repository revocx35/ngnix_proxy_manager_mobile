package io.github.revocx35.npmmobile.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Parses responses recorded from a real NPM 2.15.1 (src/test/resources/fixtures). */
class ModelParsingTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.json")!!.readText()

    @Test fun proxyHostsWithExpansions() {
        val hosts = NpmJson.decodeFromString(ListSerializer(ProxyHost.serializer()), fixture("proxy-hosts"))
        val h = hosts.single()
        assertEquals(listOf("app.test.local"), h.domainNames)
        assertEquals("http://10.0.0.5:8080", h.target)
        assertTrue(h.blockExploits)
        assertTrue(h.sslForced)
        assertTrue(h.http2Support)
        assertEquals(1, h.certificateId)
        assertEquals("My custom", h.certificate?.niceName)
        assertEquals("Office", h.accessList?.name)
        assertEquals("admin@test.local", h.owner?.email)
        assertEquals("/api", h.locations.single().path)
        assertEquals(9000, h.locations.single().forwardPort)
        assertEquals(HostStatus.Online, h.status)
        assertNull(h.nginxError)
    }

    @Test fun otherHostKinds() {
        val r = NpmJson.decodeFromString(ListSerializer(RedirectionHost.serializer()), fixture("redirection-hosts")).single()
        assertEquals("new.test.local", r.forwardDomainName)
        assertEquals(301, r.forwardHttpCode)
        assertTrue(r.preservePath)
        assertEquals("new.test.local", r.target)

        val d = NpmJson.decodeFromString(ListSerializer(DeadHost.serializer()), fixture("dead-hosts")).single()
        assertEquals(listOf("gone.test.local"), d.domainNames)

        val s = NpmJson.decodeFromString(ListSerializer(Stream.serializer()), fixture("streams")).single()
        assertEquals(2222, s.incomingPort)
        assertEquals("10.0.0.7:22", s.target)
        assertEquals("TCP", s.protocols)
    }

    @Test fun accessListsCertificatesUsers() {
        val a = NpmJson.decodeFromString(ListSerializer(AccessList.serializer()), fixture("access-lists")).single()
        assertEquals("Office", a.name)
        assertTrue(a.satisfyAny)
        assertEquals("bob", a.items.single().username)
        assertEquals("s******", a.items.single().hint)
        assertEquals("10.0.0.0/8", a.clients.single().address)
        assertEquals(1, a.proxyHostCount)

        val certs = NpmJson.decodeFromString(ListSerializer(Certificate.serializer()), fixture("certificates"))
        val custom = certs.first { it.provider == "other" }
        assertFalse(custom.isLetsEncrypt)
        assertEquals(listOf("custom.test.local"), custom.domainNames)
        assertNotNull(custom.expiresAt)

        val u = NpmJson.decodeFromString(ListSerializer(User.serializer()), fixture("users")).first()
        assertTrue(u.isAdmin)
        assertEquals("manage", u.permissions?.proxyHosts)
        assertTrue(u.canManage(Section.Certificates))
    }

    @Test fun auditLogSettingsReportsProviders() {
        val log = NpmJson.decodeFromString(ListSerializer(AuditLogEntry.serializer()), fixture("audit-log"))
        assertTrue(log.isNotEmpty())
        assertTrue(log.all { it.action.isNotEmpty() && it.objectType.isNotEmpty() })
        assertTrue(log.any { it.objectLabel == "app.test.local" })

        val settings = NpmJson.decodeFromString(ListSerializer(Setting.serializer()), fixture("settings"))
        assertEquals(Setting.DEFAULT_SITE, settings.single().id)

        val report = NpmJson.decodeFromString(HostReport.serializer(), fixture("reports"))
        assertEquals(1, report.proxy)

        val providers = NpmJson.decodeFromString(ListSerializer(DnsProvider.serializer()), fixture("dns-providers"))
        assertTrue(providers.any { it.id == "cloudflare" && it.credentials.contains("dns_cloudflare") })
    }

    @Test fun tokenAnd2faChallenge() {
        val t = NpmJson.decodeFromString(TokenResponse.serializer(), """{"token":"abc","expires":"2026-09-26T14:19:48.281Z"}""")
        assertEquals("abc", t.token)
        assertFalse(t.requires2fa)
        val c = NpmJson.decodeFromString(TokenResponse.serializer(), """{"requires_2fa":true,"challenge_token":"xyz"}""")
        assertTrue(c.requires2fa)
        assertEquals("xyz", c.challengeToken)
    }

    @Test fun lenientBooleansAndOfflineStatus() {
        val json = """{"id":3,"domain_names":["a.b"],"enabled":1,"ssl_forced":"0","http2_support":"true",
            |"meta":{"nginx_online":false,"nginx_err":"nginx: [emerg] bad"},"future_field":{"x":1}}""".trimMargin()
        val h = NpmJson.decodeFromString(ProxyHost.serializer(), json)
        assertTrue(h.enabled)
        assertFalse(h.sslForced)
        assertTrue(h.http2Support)
        assertEquals(HostStatus.Offline, h.status)
        assertEquals("nginx: [emerg] bad", h.nginxError)
        assertEquals(HostStatus.Disabled, h.copy(enabled = false).status)
    }

    @Test fun datesFromSqliteAndMysql() {
        assertEquals(Instant.parse("2026-09-25T14:19:45Z"), NpmDates.parse("2026-09-25 14:19:45"))
        assertEquals(Instant.parse("2025-10-28T04:17:54Z"), NpmDates.parse("2025-10-28T04:17:54.000Z"))
        assertEquals(Instant.parse("2025-10-28T02:17:54Z"), NpmDates.parse("2025-10-28T04:17:54+02:00"))
        assertNull(NpmDates.parse("garbage"))
        assertNull(NpmDates.parse(null))
    }

    @Test fun requestEncoding() {
        val body = ProxyHostRequest(
            domainNames = listOf("x.example.com"), forwardScheme = "http", forwardHost = "10.0.0.1", forwardPort = 80,
            certificateId = CertificateRef.RequestNew, http2Support = true,
            meta = LetsEncryptOptions(dnsChallenge = true, dnsProvider = "cloudflare", dnsProviderCredentials = "k = v").toMeta(),
        )
        val obj = NpmJson.parseToJsonElement(NpmJson.encodeToString(ProxyHostRequest.serializer(), body)).jsonObject
        assertEquals("new", obj["certificate_id"]!!.jsonPrimitive.content)
        assertEquals("true", obj["http2_support"]!!.jsonPrimitive.content)
        val meta = obj["meta"] as JsonObject
        assertEquals("cloudflare", meta["dns_provider"]!!.jsonPrimitive.content)
        assertFalse("null propagation_seconds must be omitted", meta.containsKey("propagation_seconds"))

        val existing = NpmJson.encodeToString(ProxyHostRequest.serializer(), body.copy(certificateId = CertificateRef.Existing(7), meta = null))
        val o2 = NpmJson.parseToJsonElement(existing).jsonObject
        assertEquals(7, o2["certificate_id"]!!.jsonPrimitive.content.toInt())
        assertFalse(o2.containsKey("meta"))
        val none = NpmJson.encodeToString(ProxyHostRequest.serializer(), body.copy(certificateId = CertificateRef.None, meta = null))
        assertEquals(0, NpmJson.parseToJsonElement(none).jsonObject["certificate_id"]!!.jsonPrimitive.content.toInt())

        val setting = NpmJson.encodeToString(SettingRequest.serializer(), SettingRequest.defaultSite("redirect", redirect = "https://x"))
        assertEquals("""{"value":"redirect","meta":{"redirect":"https://x"}}""", setting)
    }
}
