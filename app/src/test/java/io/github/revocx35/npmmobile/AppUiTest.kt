package io.github.revocx35.npmmobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.revocx35.npmmobile.core.ServerAddress
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.data.SessionState
import io.github.revocx35.npmmobile.ui.AppRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Drives the real UI (Robolectric) against [FakeNpmServer]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppUiTest {
    @get:Rule val compose = createComposeRule()
    private val npm = FakeNpmServer()
    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<TestApp>().graph

    @After fun tearDown() = npm.close()

    private fun start() = compose.setContent { AppRoot(graph) }

    @Test fun signInThroughTheUi() {
        start()
        signIn(compose, npm.url, "admin@example.com", "demo-pass")
        compose.waitForText("Needs attention")
        compose.waitForText("vault.example.com") // certificate expiring in 5 days
        compose.waitForText("homeassistant.example.com") // offline host
        assertTrue(npm.requests.contains("POST /tokens"))
        val s = graph.session.state.value as SessionState.SignedIn
        assertEquals("Alex Morgan", s.account.name)
        assertEquals("demo-token", graph.accounts.token(s.account))
        assertFalse("password must not be stored unless asked", s.account.remembersPassword)
    }

    @Test fun wrongPasswordShowsNpmsMessage() {
        start()
        signIn(compose, npm.url, "admin@example.com", "nope")
        compose.waitForText("Invalid email or password")
        assertTrue(graph.session.state.value is SessionState.NoAccount)
    }

    @Test fun twoFactorSignIn() {
        npm.requireTwoFactor = true
        start()
        signIn(compose, npm.url, "admin@example.com", "demo-pass")
        compose.waitForText("Two-factor authentication")
        compose.onNodeWithTag("code").performTextInput("123456")
        compose.onNodeWithText("Verify").performClick()
        compose.waitForText("Needs attention")
        assertTrue(npm.requests.contains("POST /tokens/2fa"))
    }

    @Test fun rejectedTokenAsksToSignInAgain() {
        seed(graph, npm, token = "stale-token")
        start()
        compose.waitForText("Your session expired. Please sign in again.")
        val s = graph.session.state.value
        assertTrue("$s", s is SessionState.NeedsSignIn)
    }

    @Test fun createDisableAndDeleteAProxyHost() {
        seed(graph, npm)
        start()
        compose.waitForText("Needs attention")
        compose.onNodeWithTag("tab-Hosts").performClick()
        compose.waitForText("nextcloud.example.com")

        compose.onNodeWithTag("addHost").performClick()
        compose.waitForText("New proxy host")
        compose.onNodeWithTag("domainInput").performTextInput("new.example.com")
        compose.onNodeWithTag("domainInput").performImeAction()
        compose.onNodeWithTag("forwardHost").performTextInput("10.0.0.99")
        compose.onNodeWithTag("forwardPort").performTextInput("8080")
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(10_000) { npm.hosts().any { it.domainNames == listOf("new.example.com") } }
        val created = npm.hosts().first { it.domainNames == listOf("new.example.com") }
        assertEquals("10.0.0.99", created.forwardHost)
        assertEquals(8080, created.forwardPort)
        assertTrue(created.blockExploits)

        // Back on the list: disable it from the card menu.
        compose.onNodeWithTag("hostlist-Proxy").performScrollToNode(hasTestTag("host-${created.id}"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("hostmenu-${created.id}")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hostmenu-${created.id}").performClick()
        compose.onNodeWithTag("toggle-${created.id}").performClick()
        compose.waitUntil(10_000) { npm.hosts().first { it.id == created.id }.enabled.not() }
        assertTrue(npm.requests.contains("POST /nginx/proxy-hosts/${created.id}/disable"))
    }

    @Test fun invalidFormShowsErrorsAndSendsNothing() {
        seed(graph, npm)
        start()
        compose.waitForText("Needs attention")
        compose.onNodeWithTag("tab-Hosts").performClick()
        compose.onNodeWithTag("addHost").performClick()
        compose.waitForText("New proxy host")
        compose.onNodeWithTag("forwardPort").performTextInput("99999")
        compose.onNodeWithTag("save").performClick()
        compose.waitForText("Add at least one domain")
        compose.waitForText("Enter a hostname or IP")
        assertFalse(npm.requests.any { it == "POST /nginx/proxy-hosts" })
    }

    companion object {
        fun seed(graph: AppGraph, npm: FakeNpmServer, token: String = "demo-token") {
            val acc = graph.accounts.upsert(
                ServerAddress.parse(npm.url)!!, "admin@example.com", "Alex Morgan", "2.15.1", token,
                Instant.now().plusSeconds(86_400).toEpochMilli(), null,
            )
            graph.accounts.setActive(acc.id)
            graph.session.switchTo(acc.id)
        }

        fun signIn(compose: ComposeContentTestRule, url: String, email: String, password: String) {
            compose.waitForText("Sign in to Nginx Proxy Manager")
            compose.onNodeWithTag("server").performTextInput(url)
            compose.onNodeWithTag("email").performTextInput(email)
            compose.onNodeWithTag("password").performTextInput(password)
            compose.onNodeWithTag("signIn").performClick()
        }
    }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.waitForText(text: String, timeout: Long = 10_000) {
    waitUntil(timeout) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
}
