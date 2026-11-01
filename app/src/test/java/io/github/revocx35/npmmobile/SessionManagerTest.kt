package io.github.revocx35.npmmobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.revocx35.npmmobile.core.ServerAddress
import io.github.revocx35.npmmobile.data.AccountStore
import io.github.revocx35.npmmobile.data.SessionManager
import io.github.revocx35.npmmobile.data.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionManagerTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        server.close()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun `leaving a screen while the silent re-login runs does not end the session`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api")
                seen += "${request.method} $path"
                return when {
                    path == "/users/me" -> json("""{"id":1,"email":"a@b.c","name":"A"}""")
                    // The password sign-in hangs, so the caller can be cancelled in the middle of it.
                    path == "/tokens" -> MockResponse.Builder().code(200).headersDelay(30, TimeUnit.SECONDS).build()
                    else -> json("""{"error":{"code":400,"message":"Token has expired"}}""", 400)
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().clear().commit()
        val store = AccountStore(context, FakeCipher())
        val account = store.upsert(ServerAddress.parse(server.url("/").toString())!!, "a@b.c", "A", null, "tok", null, "remembered-pw")
        store.setActive(account.id)
        val session = SessionManager(store, OkHttpClient(), scope, "test", Dispatchers.IO)
        session.start()
        waitFor { (session.state.value as? SessionState.SignedIn)?.user != null }

        val job = launch(Dispatchers.IO) { session.call { it.proxyHosts() } }
        waitFor { "POST /tokens" in seen }
        job.cancelAndJoin()

        val state = session.state.value
        assertTrue("session must survive a cancelled caller, was $state", state is SessionState.SignedIn)
        assertEquals("tok", store.token(store.accounts.value.first()))
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out; requests: $seen" }
            Thread.sleep(20)
        }
    }
}
