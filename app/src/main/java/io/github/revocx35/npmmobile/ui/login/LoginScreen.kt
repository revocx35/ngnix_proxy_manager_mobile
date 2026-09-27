package io.github.revocx35.npmmobile.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.revocx35.npmmobile.R
import io.github.revocx35.npmmobile.core.HealthStatus
import io.github.revocx35.npmmobile.core.NpmClient
import io.github.revocx35.npmmobile.core.NpmException
import io.github.revocx35.npmmobile.core.ServerAddress
import io.github.revocx35.npmmobile.core.Validation
import io.github.revocx35.npmmobile.data.Account
import io.github.revocx35.npmmobile.data.SessionManager
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalNetworkAccess
import io.github.revocx35.npmmobile.ui.UiError
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.PasswordField
import io.github.revocx35.npmmobile.ui.components.PrimaryButton
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LoginViewModel(
    private val session: SessionManager,
    prefill: Account?,
    /** True if Android 17's local network permission must be granted before connecting to this address. */
    private val needsLocalNetwork: suspend (ServerAddress) -> Boolean = { false },
    /** True while the permission is enforced (Android 17+) and not granted. */
    private val localNetworkMissing: () -> Boolean = { false },
) : ViewModel() {
    var server by mutableStateOf(prefill?.server ?: "")
    var email by mutableStateOf(prefill?.email ?: "")
    var password by mutableStateOf("")
    var code by mutableStateOf("")
    var remember by mutableStateOf(prefill?.remembersPassword ?: false)
    var allowInsecure by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    var challenge by mutableStateOf<String?>(null)
        private set
    var health by mutableStateOf<HealthStatus?>(null)
        private set
    var showErrors by mutableStateOf(false)
        private set
    /** The screen should ask for the local network permission now (then call [onLocalNetworkResult]). */
    var askLocalNetwork by mutableStateOf(false)
        private set
    /** A connection failed while the permission is missing: offer it (servers not recognized as local). */
    var offerLocalNetwork by mutableStateOf(false)
        private set
    var localNetworkDenied by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var client: NpmClient? = null

    val address: ServerAddress? get() = ServerAddress.parse(server)

    /** Plain HTTP to something that isn't a LAN address sends the password unencrypted over the internet. */
    val needsInsecureConsent: Boolean get() = address?.let { !it.isHttps && !it.isLocalNetwork } ?: false

    val serverError: String? get() = if (server.isBlank()) "Enter the address of your NPM admin UI" else if (address == null) "Not a valid address" else null
    val emailError: String? get() = Validation.emailError(email)
    val passwordError: String? get() = if (password.isEmpty()) "Enter your password" else null

    fun submit() {
        if (busy != null) return
        if (challenge != null) return submitCode()
        showErrors = true
        if (serverError != null || emailError != null || passwordError != null) return
        if (needsInsecureConsent && !allowInsecure) {
            error = UiError("Confirm that you want to send your password over unencrypted HTTP, or use https://.")
            return
        }
        val addr = address ?: return
        error = null
        offerLocalNetwork = false
        job = viewModelScope.launch {
            try {
                busy = "Checking server…"
                if (needsLocalNetwork(addr)) {
                    askLocalNetwork = true
                    return@launch
                }
                val c = session.newClient(addr)
                client = c
                val h = c.health()
                health = h
                if (!h.setup) throw NpmException.NotNpm("This NPM instance isn't set up yet. Finish the first-run setup in its web UI.")
                busy = "Signing in…"
                when (val r = c.login(email.trim(), password)) {
                    is NpmClient.LoginResult.Success -> finish(addr, r.token, twoFactor = false)
                    is NpmClient.LoginResult.TwoFactorRequired -> {
                        challenge = r.challengeToken
                        code = ""
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.toUiError()
                if (e is NpmException.Network && localNetworkMissing()) offerLocalNetwork = true
            } finally {
                busy = null
            }
        }
    }

    fun onLocalNetworkResult(granted: Boolean) {
        askLocalNetwork = false
        offerLocalNetwork = false
        localNetworkDenied = !granted
        if (granted) submit() else error = UiError(LocalNetworkAccess.DENIED_MESSAGE)
    }

    private fun submitCode() {
        val addr = address ?: return
        val ch = challenge ?: return
        val c = client ?: return
        val clean = code.filterNot { it.isWhitespace() }
        if (clean.length !in 6..8) {
            error = UiError("Enter the 6-digit code from your authenticator app or an 8-character backup code.")
            return
        }
        error = null
        job = viewModelScope.launch {
            try {
                busy = "Verifying code…"
                finish(addr, c.loginWith2fa(ch, clean), twoFactor = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }

    private suspend fun finish(addr: ServerAddress, token: io.github.revocx35.npmmobile.core.AuthToken, twoFactor: Boolean) {
        busy = "Loading your account…"
        // A remembered password can't renew a 2FA session silently, so it isn't stored for 2FA accounts.
        session.completeSignIn(addr, email.trim(), token, if (remember && !twoFactor) password else null, health)
    }

    fun cancelTwoFactor() {
        job?.cancel()
        challenge = null
        code = ""
        error = null
    }
}

@Composable
fun LoginScreen(prefill: Account?, reason: String?, onCancel: (() -> Unit)?) {
    val graph = LocalGraph.current
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val vm: LoginViewModel = viewModel(key = "login-${prefill?.id}") {
        LoginViewModel(
            graph.session, prefill,
            needsLocalNetwork = { LocalNetworkAccess.needed(context, it) },
            localNetworkMissing = { !LocalNetworkAccess.granted(context) },
        )
    }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val localNetworkLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.onLocalNetworkResult(it) }
    LaunchedEffect(vm.askLocalNetwork) { if (vm.askLocalNetwork) localNetworkLauncher.launch(LocalNetworkAccess.PERMISSION) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onCancel != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.Start)) {
                        Icon(AppIcons.Back, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Back")
                    }
                } else {
                    Spacer(Modifier.height(24.dp))
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally)) {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(0.dp))
                }
                Text(
                    if (vm.challenge != null) "Two-factor authentication" else "Sign in to Nginx Proxy Manager",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                vm.health?.version?.let {
                    Text(
                        "${vm.address?.baseUrl?.host} · NPM $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (reason != null && vm.error == null && vm.challenge == null) InfoBanner(reason, icon = AppIcons.Schedule)
                vm.error?.let { ErrorCard(it.message, details = it.details) }
                if (vm.offerLocalNetwork) {
                    io.github.revocx35.npmmobile.ui.components.TonalButton(
                        "Allow local network access", { localNetworkLauncher.launch(LocalNetworkAccess.PERMISSION) },
                        Modifier.fillMaxWidth(), icon = AppIcons.Lan,
                    )
                }
                if (vm.localNetworkDenied) {
                    TextButton(onClick = { LocalNetworkAccess.openAppSettings(context) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Open app settings")
                    }
                }

                if (vm.challenge == null) {
                    FormTextField(
                        value = vm.server, onValueChange = { vm.server = it }, label = "Server address",
                        placeholder = "npm.example.com or http://192.168.1.10:81",
                        error = if (vm.showErrors) vm.serverError else null,
                        supportingText = "The address of the NPM admin UI (usually port 81)",
                        keyboardType = KeyboardType.Uri, leadingIcon = AppIcons.Public,
                        modifier = Modifier.testTag("server"),
                    )
                    if (vm.needsInsecureConsent) {
                        InfoBanner("This address uses plain HTTP over the internet: your password and session would be readable by anyone on the way.", warning = true, icon = AppIcons.Warning)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = vm.allowInsecure, onCheckedChange = { vm.allowInsecure = it })
                            Text("I understand, use HTTP anyway", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    FormTextField(
                        value = vm.email, onValueChange = { vm.email = it }, label = "E-mail",
                        error = if (vm.showErrors) vm.emailError else null,
                        keyboardType = KeyboardType.Email, leadingIcon = AppIcons.Mail,
                        modifier = Modifier.testTag("email"),
                    )
                    PasswordField(
                        value = vm.password, onValueChange = { vm.password = it }, label = "Password",
                        error = if (vm.showErrors) vm.passwordError else null,
                        imeAction = ImeAction.Done, onImeAction = { focus.clearFocus(); vm.submit() },
                        modifier = Modifier.testTag("password"),
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(checked = vm.remember, onCheckedChange = { vm.remember = it })
                        Column(Modifier.padding(top = 12.dp)) {
                            Text("Stay signed in", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "NPM sessions last one day. With this on, your password is kept encrypted on this device to renew them. Not used for accounts with 2FA.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    PrimaryButton(
                        text = vm.busy ?: "Sign in",
                        onClick = { focus.clearFocus(); vm.submit() },
                        busy = vm.busy != null,
                        modifier = Modifier.testTag("signIn"),
                    )
                } else {
                    Text(
                        "Enter the code from your authenticator app, or one of your backup codes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FormTextField(
                        value = vm.code, onValueChange = { v -> vm.code = v.filter { it.isLetterOrDigit() }.take(8) }, label = "Code",
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, onImeAction = { vm.submit() },
                        leadingIcon = AppIcons.Key, modifier = Modifier.testTag("code"),
                    )
                    PrimaryButton(text = vm.busy ?: "Verify", onClick = { vm.submit() }, busy = vm.busy != null)
                    TextButton(onClick = { vm.cancelTwoFactor() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use another account") }
                }

                SavedAccounts(current = prefill)
            }
        }
    }
}

/** Other saved servers, so a user with several NPM instances can switch without typing. */
@Composable
private fun SavedAccounts(current: Account?) {
    val graph = LocalGraph.current
    val accounts by graph.session.accounts.collectAsStateWithLifecycle()
    val others = accounts.filter { it.id != current?.id }
    if (others.isEmpty() && current == null) return
    Spacer(Modifier.height(8.dp))
    if (others.isNotEmpty()) {
        Text("Saved servers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        others.forEach { acc ->
            io.github.revocx35.npmmobile.ui.components.NavRow(
                title = acc.host,
                subtitle = acc.email,
                icon = AppIcons.Server,
                onClick = { graph.session.switchTo(acc.id) },
            )
        }
    }
    if (current != null) {
        TextButton(onClick = { graph.session.addAnother() }, modifier = Modifier.fillMaxWidth()) { Text("Sign in to another server") }
    }
}
