package io.github.revocx35.npmmobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.data.ThemeMode
import io.github.revocx35.npmmobile.ui.AppRoot
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with sample data to build/screenshots/ (`./gradlew :app:testDebugUnitTest
 * --tests '*ScreenshotTest*'`). Used to review the UI without a device, and for the README images.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val npm = FakeNpmServer()
    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<TestApp>().graph

    @After fun tearDown() = npm.close()

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun signedIn(theme: ThemeMode, waitFor: String = "vault.example.com") {
        graph.prefs.setDynamicColor(false)
        graph.prefs.setTheme(theme)
        AppUiTest.seed(graph, npm)
        compose.setContent { AppRoot(graph) }
        compose.waitForText(waitFor)
    }

    private fun tab(label: String) = compose.onNodeWithTag("tab-$label").performClick()

    @Test fun login() {
        graph.prefs.setDynamicColor(false)
        compose.setContent { AppRoot(graph) }
        compose.waitForText("Sign in to Nginx Proxy Manager")
        shot("01_login")
    }

    @Test fun lightWalkthrough() {
        signedIn(ThemeMode.Light)
        shot("02_dashboard")

        tab("Hosts")
        compose.waitForText("nextcloud.example.com")
        shot("03_proxy_hosts")
        compose.onNodeWithTag("hosttab-Redirection").performClick()
        compose.waitForText("www.example.com")
        shot("04_redirections")
        compose.onNodeWithTag("hosttab-Stream").performClick()
        compose.waitForText("Port 25565")
        shot("05_streams")

        compose.onNodeWithTag("hosttab-Proxy").performClick()
        compose.waitForText("grafana.example.com")
        compose.onNodeWithText("grafana.example.com").performClick()
        compose.waitForText("Edit proxy host")
        compose.waitForText("Forward to")
        shot("06_proxy_edit_details")
        compose.onNodeWithTag("edittab-Locations").performClick()
        compose.waitForText("Location 2")
        shot("07_proxy_edit_locations")
        compose.onNodeWithTag("edittab-SSL").performClick()
        compose.waitForText("Force SSL")
        shot("08_proxy_edit_ssl")
        pressBack()

        compose.waitForText("homeassistant.example.com")
        compose.onNodeWithText("homeassistant.example.com").performClick()
        compose.waitForText("nginx rejected the configuration")
        shot("09_proxy_offline")
        pressBack()

        tab("SSL")
        compose.waitForText("Internal CA")
        shot("10_certificates")
        compose.onNodeWithText("vault.example.com").performClick()
        compose.waitForText("Renew now")
        shot("11_certificate_detail")
        pressBack()

        compose.onNodeWithTag("addCert").performClick()
        compose.onNodeWithText("Let's Encrypt").performClick()
        compose.waitForText("Test HTTP reachability")
        compose.onNodeWithText("Use a DNS challenge").performClick()
        compose.waitForText("DNS provider")
        shot("12_new_letsencrypt")
        pressBack()

        tab("More")
        compose.waitForText("Access lists")
        shot("13_more")
        compose.onNodeWithText("Access lists").performClick()
        compose.waitForText("Family")
        shot("14_access_lists")
        compose.onNodeWithText("Office network only").performClick()
        compose.waitForText("IP address rules")
        shot("15_access_list_edit")
        pressBack()
        pressBack()

        compose.onNodeWithText("Users").performClick()
        compose.waitForText("Jane Doe")
        shot("16_users")
        compose.onNodeWithText("Jane Doe").performClick()
        compose.waitForText("Permissions")
        shot("17_user_edit")
        pressBack()
        pressBack()

        compose.onNodeWithText("Audit log").performClick()
        compose.waitForText("renewed certificate")
        shot("18_audit_log")
        pressBack()

        compose.onNodeWithTag("profileCard").performClick()
        compose.waitForText("Two-factor authentication")
        shot("19_profile")
    }

    @Test fun darkWalkthrough() {
        signedIn(ThemeMode.Dark)
        shot("20_dashboard_dark")
        tab("Hosts")
        compose.waitForText("nextcloud.example.com")
        shot("21_proxy_hosts_dark")
        compose.onNodeWithText("nextcloud.example.com").performClick()
        compose.waitForText("Forward to")
        shot("22_proxy_edit_dark")
        pressBack()
        tab("SSL")
        compose.waitForText("Internal CA")
        shot("23_certificates_dark")
    }

    @Config(qualifiers = "w840dp-h600dp-xhdpi")
    @Test fun tabletLandscape() {
        signedIn(ThemeMode.Light, waitFor = "Redirections")
        shot("24_dashboard_tablet")
        tab("Hosts")
        compose.waitForText("nextcloud.example.com")
        shot("25_hosts_tablet")
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
