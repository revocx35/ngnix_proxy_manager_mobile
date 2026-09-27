package io.github.revocx35.npmmobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {
    private fun url(input: String) = ServerAddress.parse(input)?.display

    @Test fun serverAddressNormalization() {
        assertEquals("https://ngnix.mikoyan.men", url("ngnix.mikoyan.men"))
        assertEquals("https://ngnix.mikoyan.men", url("  https://ngnix.mikoyan.men/  "))
        assertEquals("https://ngnix.mikoyan.men", url("https://ngnix.mikoyan.men/api/"))
        assertEquals("https://ngnix.mikoyan.men", url("https://ngnix.mikoyan.men/login"))
        assertEquals("https://ngnix.mikoyan.men", url("https://ngnix.mikoyan.men/nginx/proxy?x=1#y"))
        assertEquals("http://192.168.1.10:81", url("http://192.168.1.10:81"))
        assertEquals("https://example.com/npm", url("https://example.com/npm/api/tokens"))
        assertNull(url(""))
        assertNull(url("not a url"))
        assertNull(url("ftp://example.com"))
        assertEquals("https://ngnix.mikoyan.men/api/nginx/proxy-hosts", ServerAddress.parse("ngnix.mikoyan.men")!!.resolve("nginx/proxy-hosts").toString())
        assertEquals("https://example.com/npm/api/", ServerAddress.parse("example.com/npm")!!.apiUrl.toString())
    }

    @Test fun localNetworkDetection() {
        listOf("localhost", "127.0.0.1", "10.1.2.3", "192.168.68.19", "172.16.0.1", "nas.local", "router.lan", "fd00::1", "100.101.102.103", "[fe80::1]")
            .forEach { assertTrue(it, ServerAddress.isLocalHost(it)) }
        listOf("ngnix.mikoyan.men", "8.8.8.8", "172.32.0.1", "dead.beef.cafe.face", "2001:db8::1")
            .forEach { assertFalse(it, ServerAddress.isLocalHost(it)) }
    }

    @Test fun lanAddressesForAndroid17Permission() {
        listOf("192.168.1.10", "10.0.2.2", "172.20.1.1", "169.254.1.1", "fd12::1", "nas.local", "router.lan")
            .forEach { assertTrue(it, ServerAddress.resolvesToLan(it)) }
        // Loopback, public and CGNAT (Tailscale, via VPN) addresses don't need the local network permission.
        listOf("127.0.0.1", "8.8.8.8", "100.101.102.103", "2001:db8::1")
            .forEach { assertFalse(it, ServerAddress.resolvesToLan(it)) }
    }

    @Test fun domains() {
        assertEquals(listOf("a.com", "b.com", "*.c.com"), Validation.splitDomains("A.com, b.com\n*.c.com a.com"))
        assertNull(Validation.domainError("*.example.com"))
        assertNull(Validation.domainError("xn--bcher-kva.example"))
        assertNotNull(Validation.domainError("http://x.com"))
        assertNotNull(Validation.domainError("a b"))
        assertNotNull(Validation.domainError(".x.com"))
    }

    @Test fun hostsPortsAddresses() {
        assertNull(Validation.forwardHostError("10.0.0.5"))
        assertNull(Validation.forwardHostError("my-service"))
        assertNull(Validation.forwardHostError("backend/app", allowPath = true))
        assertNotNull(Validation.forwardHostError("backend/app"))
        assertNotNull(Validation.forwardHostError("http://x"))
        assertNotNull(Validation.forwardHostError(""))
        assertNull(Validation.portError(81))
        assertNotNull(Validation.portError(0))
        assertNotNull(Validation.portError(70000))
        assertNotNull(Validation.portError(null))
        assertNull(Validation.clientAddressError("10.0.0.0/8"))
        assertNull(Validation.clientAddressError("192.168.1.5"))
        assertNull(Validation.clientAddressError("all"))
        assertNull(Validation.clientAddressError("2001:db8::/32"))
        assertNotNull(Validation.clientAddressError("300.1.1.1"))
        assertNotNull(Validation.clientAddressError("10.0.0.0/40"))
        assertNotNull(Validation.clientAddressError("example.com"))
        assertNull(Validation.emailError("a@b.co"))
        assertNotNull(Validation.emailError("ab.co"))
    }

    @Test fun permissions() {
        val viewer = User(roles = emptyList(), permissions = UserPermissions(proxyHosts = "view", certificates = "hidden"))
        assertTrue(viewer.canView(Section.ProxyHosts))
        assertFalse(viewer.canManage(Section.ProxyHosts))
        assertFalse(viewer.canView(Section.Certificates))
        assertTrue(viewer.canManage(Section.Streams))
        val admin = User(roles = listOf("admin"), permissions = UserPermissions(certificates = "hidden"))
        assertTrue(admin.canManage(Section.Certificates))
    }
}
