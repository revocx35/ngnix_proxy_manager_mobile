package io.github.revocx35.npmmobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.revocx35.npmmobile.core.HostKind
import io.github.revocx35.npmmobile.core.NpmClient
import io.github.revocx35.npmmobile.core.ServerAddress
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.ui.AppRoot
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real UI against a real, disposable NPM (same env vars as core's LiveNpmTest). Signs in through the
 * login screen, visits every tab, creates a proxy host with the editor and removes it again.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LiveAppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val url = System.getenv("NPM_IT_URL")
    private val email = System.getenv("NPM_IT_EMAIL")
    private val password = System.getenv("NPM_IT_PASSWORD")
    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<TestApp>().graph

    @Test fun signInBrowseCreateAndDelete() {
        assumeTrue("NPM_IT_URL not set", !url.isNullOrBlank() && !email.isNullOrBlank() && !password.isNullOrBlank())
        val domain = "ui-${(100000..999999).random()}.it-test.invalid"
        compose.setContent { AppRoot(graph) }
        AppUiTest.signIn(compose, url, email, password)
        compose.waitForText("Needs attention", 30_000)

        compose.onNodeWithTag("tab-SSL").performClick()
        compose.waitForText("SSL certificates", 30_000)
        compose.onNodeWithTag("tab-More").performClick()
        compose.onNodeWithText("Audit log").performClick()
        compose.waitForText("Audit log")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithTag("tab-Hosts").performClick()
        compose.onNodeWithTag("addHost").performClick()
        compose.waitForText("New proxy host")
        compose.onNodeWithTag("domainInput").performTextInput(domain)
        compose.onNodeWithTag("domainInput").performImeAction()
        compose.onNodeWithTag("forwardHost").performTextInput("10.9.8.7")
        compose.onNodeWithTag("forwardPort").performTextInput("8088")
        compose.onNodeWithTag("save").performClick()
        compose.waitForText(domain, 60_000)

        val client = NpmClient(ServerAddress.parse(url)!!, OkHttpClient())
        runBlocking {
            client.token = (client.login(email, password) as NpmClient.LoginResult.Success).token.token
            val host = client.proxyHosts().first { it.domainNames == listOf(domain) }
            try {
                assertEquals("10.9.8.7", host.forwardHost)
                assertEquals(8088, host.forwardPort)
                assertEquals(true, client.proxyHost(host.id).nginxOnline)
            } finally {
                client.deleteHost(HostKind.Proxy, host.id)
            }
        }
    }
}
