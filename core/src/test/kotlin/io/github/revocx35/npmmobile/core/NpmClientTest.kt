package io.github.revocx35.npmmobile.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class NpmClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NpmClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = NpmClient(ServerAddress.parse(server.url("/").toString())!!, OkHttpClient(), token = "tok")
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
        }
        fail("Expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test fun healthNeedsNoTokenAndChecksStatus() = runBlocking {
        server.enqueue(json("""{"status":"OK","setup":true,"version":{"major":2,"minor":15,"revision":1}}"""))
        val h = client.health()
        assertEquals("2.15.1", h.version.toString())
        val req = server.takeRequest()
        assertEquals("/api/", req.target)
        assertNull(req.headers["Authorization"])

        server.enqueue(json("""{"status":"nope"}"""))
        expect<NpmException.NotNpm> { runBlocking { client.health() } }
        Unit
    }

    @Test fun loginWithAndWithout2fa() = runBlocking {
        server.enqueue(json("""{"token":"T1","expires":"2026-09-26T14:19:48.281Z"}"""))
        val ok = client.login(" admin@x.com ", "pw") as NpmClient.LoginResult.Success
        assertEquals("T1", ok.token.token)
        assertEquals(java.time.Instant.parse("2026-09-26T14:19:48.281Z"), ok.token.expiresAt)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/tokens", req.target)
        assertEquals("""{"identity":"admin@x.com","secret":"pw","scope":"user"}""", req.body?.utf8())

        server.enqueue(json("""{"requires_2fa":true,"challenge_token":"CH"}"""))
        val challenge = client.login("a@b.c", "pw") as NpmClient.LoginResult.TwoFactorRequired
        assertEquals("CH", challenge.challengeToken)
        server.takeRequest()

        server.enqueue(json("""{"token":"T2","expires":"2026-09-26T14:19:48.281Z"}"""))
        assertEquals("T2", client.loginWith2fa("CH", "123 456").token)
        assertEquals("""{"challenge_token":"CH","code":"123456"}""", server.takeRequest().body?.utf8())
    }

    @Test fun wrongPasswordIsAnApiErrorWithNpmsMessage() = runBlocking {
        server.enqueue(json("""{"error":{"code":400,"message":"Invalid email or password","message_i18n":"error.invalid-auth"}}""", 400))
        val e = expect<NpmException.Api> { runBlocking { client.login("a@b.c", "x") } }
        assertEquals(400, e.status)
        assertEquals("Invalid email or password", e.message)
    }

    @Test fun sendsBearerTokenAndMapsAuthErrors() = runBlocking {
        server.enqueue(json("""[]"""))
        client.proxyHosts()
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals("/api/nginx/proxy-hosts?expand=owner,access_list,certificate", req.target)

        server.enqueue(json("""{"error":{"code":400,"message":"Token has expired"}}""", 400))
        expect<NpmException.Unauthorized> { runBlocking { client.me() } }
        server.enqueue(json("""{"error":{"code":403,"message":"Permission Denied"}}""", 403))
        expect<NpmException.Forbidden> { runBlocking { client.users() } }
        server.enqueue(json("""{"error":{"code":401,"message":"Unauthorized"}}""", 401))
        expect<NpmException.Unauthorized> { runBlocking { client.users() } }

        client.token = null
        expect<NpmException.Unauthorized> { runBlocking { client.users() } }
        Unit
    }

    @Test fun apiErrorsCarryCertbotDetails() = runBlocking {
        server.enqueue(
            json(
                """{"error":{"code":500,"message":"Internal Error"},"debug":{"stack":["Error: Saving debug log","Some challenges have failed.",""]}}""",
                500,
            ),
        )
        val e = expect<NpmException.Api> { runBlocking { client.createCertificate(CertificateRequest.letsEncrypt(listOf("a.b"), LetsEncryptOptions())) } }
        assertEquals(500, e.status)
        assertEquals("Error: Saving debug log\nSome challenges have failed.", e.details)
    }

    @Test fun htmlAnswersAreNotNpm() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<!doctype html><html>Login</html>").build())
        expect<NpmException.NotNpm> { runBlocking { client.health() } }
        server.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "text/html").body("<html>404</html>").build())
        expect<NpmException.NotNpm> { runBlocking { client.health() } }
        server.enqueue(MockResponse.Builder().code(502).addHeader("Content-Type", "text/html").body("<html>Bad Gateway</html>").build())
        val e = expect<NpmException.Api> { runBlocking { client.health() } }
        assertEquals(502, e.status)
    }

    @Test fun networkErrorsAreMapped() = runBlocking {
        val dead = NpmClient(ServerAddress.parse("http://127.0.0.1:1")!!, OkHttpClient())
        val e = expect<NpmException.Network> { runBlocking { dead.health() } }
        assertEquals(NpmException.Network.Kind.Refused, e.kind)
    }

    @Test fun hostActionsAndBodies() = runBlocking {
        server.enqueue(json("true"))
        server.enqueue(json("true"))
        server.enqueue(json("true"))
        client.enableHost(HostKind.Redirection, 4)
        client.disableHost(HostKind.Stream, 5)
        client.deleteHost(HostKind.Dead, 6)
        assertEquals("POST /api/nginx/redirection-hosts/4/enable", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals("POST /api/nginx/streams/5/disable", server.takeRequest().let { "${it.method} ${it.target}" })
        assertEquals("DELETE /api/nginx/dead-hosts/6", server.takeRequest().let { "${it.method} ${it.target}" })

        server.enqueue(json("""{"id":9,"domain_names":["a.b"],"forward_host":"h","forward_port":1}"""))
        client.updateProxyHost(9, ProxyHostRequest(listOf("a.b"), "http", "h", 1, certificateId = CertificateRef.Existing(2)))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val body = put.body!!.utf8()
        assertTrue(body, body.contains("\"certificate_id\":2") && body.contains("\"forward_host\":\"h\"") && !body.contains("meta"))
    }

    @Test fun certificateUploadIsMultipart() = runBlocking {
        server.enqueue(json("""{"certificate":{"cn":"x","issuer":"y","dates":{"from":1,"to":2}},"certificate_key":true}"""))
        val v = client.validateCertificate(CertificateFiles("CERT".toByteArray(), "KEY".toByteArray()))
        assertEquals("x", v.certificate?.cn)
        assertTrue(v.certificateKey)
        val req = server.takeRequest()
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = req.body!!.utf8()
        assertTrue(body.contains("name=\"certificate\"") && body.contains("name=\"certificate_key\"") && !body.contains("intermediate"))
    }

    @Test fun auditLogQueryIsEncoded() = runBlocking {
        server.enqueue(json("[]"))
        client.auditLog("a b&c")
        assertEquals("/api/audit-log?expand=user&query=a+b%26c", server.takeRequest().target)
    }

    @Test fun cancellingTheCallerCancelsTheRequest() = runBlocking {
        server.enqueue(json("[]").newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val job = async { client.proxyHosts() }
        delay(200)
        val started = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        assertTrue("cancel took too long", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
    }

    // Android throws NetworkOnMainThreadException for socket reads on the main thread; the JVM doesn't.
    // Recreate the rule: sockets refuse reads on threads named "main-ui", then call the client from one.
    private class GuardedSocket : Socket() {
        override fun getInputStream(): InputStream = object : FilterInputStream(super.getInputStream()) {
            private fun check() {
                if (Thread.currentThread().name.startsWith("main-ui")) throw IllegalStateException("socket read on the UI thread")
            }
            override fun read(): Int { check(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return super.read(b, off, len) }
        }
    }

    @Test fun responsesAreNeverReadOnTheCallingThread() = runBlocking {
        val factory = object : SocketFactory() {
            override fun createSocket() = GuardedSocket()
            override fun createSocket(h: String?, p: Int) = throw UnsupportedOperationException()
            override fun createSocket(h: String?, p: Int, l: InetAddress?, lp: Int) = throw UnsupportedOperationException()
            override fun createSocket(h: InetAddress?, p: Int) = throw UnsupportedOperationException()
            override fun createSocket(h: InetAddress?, p: Int, l: InetAddress?, lp: Int) = throw UnsupportedOperationException()
        }
        val hosts = (1..300).joinToString(",", "[", "]") { """{"id":$it,"domain_names":["h$it.example.com"],"forward_host":"10.0.0.$it","forward_port":80}""" }
        server.enqueue(json(hosts).newBuilder().throttleBody(4096, 10, TimeUnit.MILLISECONDS).build())
        val guarded = NpmClient(client.server, OkHttpClient.Builder().socketFactory(factory).build(), token = "t")
        val ui = Executors.newSingleThreadExecutor { Thread(it, "main-ui") }.asCoroutineDispatcher()
        val list = withContext(ui) { guarded.proxyHosts() }
        assertEquals(300, list.size)
        ui.close()
    }
}
