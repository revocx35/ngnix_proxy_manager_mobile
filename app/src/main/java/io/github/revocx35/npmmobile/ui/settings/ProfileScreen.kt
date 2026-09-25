package io.github.revocx35.npmmobile.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.revocx35.npmmobile.core.NpmClient
import io.github.revocx35.npmmobile.core.TwoFactorSetup
import io.github.revocx35.npmmobile.core.TwoFactorStatus
import io.github.revocx35.npmmobile.core.User
import io.github.revocx35.npmmobile.core.UserRequest
import io.github.revocx35.npmmobile.core.Validation
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.data.SessionState
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.UiError
import io.github.revocx35.npmmobile.ui.components.AppTopBar
import io.github.revocx35.npmmobile.ui.components.BusyDialog
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.FormCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.PasswordField
import io.github.revocx35.npmmobile.ui.components.PrimaryButton
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.SwitchRow
import io.github.revocx35.npmmobile.ui.components.TonalButton
import io.github.revocx35.npmmobile.ui.copyToClipboard
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileViewModel(private val graph: AppGraph, user: User?) : ViewModel() {
    private val me get() = (graph.session.state.value as? SessionState.SignedIn)?.user
    var name by mutableStateOf(user?.name.orEmpty())
    var nickname by mutableStateOf(user?.nickname.orEmpty())
    var email by mutableStateOf(user?.email.orEmpty())
    var currentPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirmPassword by mutableStateOf("")
    var twoFactor by mutableStateOf<TwoFactorStatus?>(null)
        private set
    var setup by mutableStateOf<TwoFactorSetup?>(null)
        private set
    var setupCode by mutableStateOf("")
    var backupCodes by mutableStateOf<List<String>?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
    var message by mutableStateOf<String?>(null)

    init { loadTwoFactor() }

    private fun loadTwoFactor() {
        val id = me?.id ?: return
        viewModelScope.launch { runCatching { graph.session.call { it.twoFactorStatus(id) } }.onSuccess { twoFactor = it } }
    }

    private fun act(label: String, block: suspend (NpmClient, Int) -> Unit) {
        val id = me?.id ?: return
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                graph.session.call { block(it, id) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }

    val emailError get() = Validation.emailError(email)

    fun saveDetails() {
        val u = me ?: return
        if (emailError != null || name.isBlank()) return
        act("Saving…") { c, id ->
            val saved = c.updateUser(id, UserRequest(name.trim(), nickname.trim(), email.trim(), u.roles, u.isDisabled))
            graph.session.updateUser(saved)
            message = "Profile saved"
        }
    }

    val passwordError get() = when {
        newPassword.isNotEmpty() && newPassword.length < 8 -> "At least 8 characters"
        confirmPassword.isNotEmpty() && confirmPassword != newPassword -> "Passwords don't match"
        else -> null
    }

    fun changePassword() {
        if (currentPassword.isEmpty() || newPassword.length < 8 || newPassword != confirmPassword) return
        val remembered = (graph.session.state.value as? SessionState.SignedIn)?.account?.remembersPassword == true
        act("Changing password…") { c, id ->
            c.changePassword(id, newPassword, currentPassword)
            if (remembered) graph.session.setRememberPassword(newPassword)
            currentPassword = ""; newPassword = ""; confirmPassword = ""
            message = "Password changed"
        }
    }

    fun startSetup() = act("Starting 2FA setup…") { c, id -> setup = c.startTwoFactorSetup(id); setupCode = "" }

    fun confirmSetup() = act("Verifying…") { c, id ->
        backupCodes = c.enableTwoFactor(id, setupCode).backupCodes
        setup = null
        twoFactor = c.twoFactorStatus(id)
        // A remembered password can't renew a 2FA session: stop keeping it.
        graph.session.setRememberPassword(null)
    }

    fun cancelSetup() { setup = null }

    fun disable(code: String) = act("Turning off 2FA…") { c, id ->
        c.disableTwoFactor(id, code)
        twoFactor = c.twoFactorStatus(id)
        message = "Two-factor authentication is off"
    }

    fun regenerate(code: String) = act("Creating new backup codes…") { c, id ->
        backupCodes = c.regenerateBackupCodes(id, code).backupCodes
        twoFactor = c.twoFactorStatus(id)
    }

    /** "Stay signed in": checks the password with a real sign-in before keeping it (encrypted). */
    fun setRemember(password: String?) {
        if (password == null) {
            graph.session.setRememberPassword(null)
            message = "Password no longer kept on this device"
            return
        }
        val account = (graph.session.state.value as? SessionState.SignedIn)?.account ?: return
        busy = "Checking password…"
        error = null
        viewModelScope.launch {
            try {
                when (graph.session.newClient(account.address).login(account.email, password)) {
                    is NpmClient.LoginResult.Success -> {
                        graph.session.setRememberPassword(password)
                        message = "You'll stay signed in on this device"
                    }
                    is NpmClient.LoginResult.TwoFactorRequired -> error = UiError("Accounts with 2FA can't stay signed in this way.")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val session = LocalSession.current
    val context = LocalContext.current
    val vm: ProfileViewModel = viewModel { ProfileViewModel(graph, session.user) }
    val snackbar = remember { SnackbarHostState() }
    var codeDialog by remember { mutableStateOf<String?>(null) } // "disable" or "regenerate"
    var rememberDialog by remember { mutableStateOf(false) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(topBar = { AppTopBar("Your account", onBack = { nav.popBackStack() }, subtitle = session.account.host) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.error?.let { ErrorCard(it.message, details = it.details) }

            SectionHeader("Details", inset = false)
            FormTextField(vm.name, { vm.name = it }, "Full name", error = if (vm.name.isBlank()) "Enter a name" else null)
            FormTextField(vm.nickname, { vm.nickname = it }, "Nickname")
            FormTextField(vm.email, { vm.email = it }, "E-mail", error = vm.emailError, keyboardType = KeyboardType.Email, supportingText = "Also used for Let's Encrypt registration")
            TonalButton("Save details", { vm.saveDetails() }, Modifier.fillMaxWidth(), icon = AppIcons.Check)

            SectionHeader("Password", inset = false)
            PasswordField(vm.currentPassword, { vm.currentPassword = it }, "Current password")
            PasswordField(vm.newPassword, { vm.newPassword = it }, "New password")
            PasswordField(vm.confirmPassword, { vm.confirmPassword = it }, "Repeat new password", error = vm.passwordError)
            TonalButton(
                "Change password", { vm.changePassword() }, Modifier.fillMaxWidth(), icon = AppIcons.Key,
                enabled = vm.currentPassword.isNotEmpty() && vm.newPassword.length >= 8 && vm.newPassword == vm.confirmPassword,
            )

            SectionHeader("Two-factor authentication", inset = false)
            val tf = vm.twoFactor
            when {
                vm.setup != null -> TwoFactorSetupCard(vm)
                tf == null -> Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                tf.enabled -> {
                    InfoBanner("2FA is on. ${tf.backupCodesRemaining} backup code(s) left.", icon = AppIcons.Security)
                    TonalButton("New backup codes", { codeDialog = "regenerate" }, Modifier.fillMaxWidth(), icon = AppIcons.Renew)
                    TonalButton("Turn off 2FA", { codeDialog = "disable" }, Modifier.fillMaxWidth(), icon = AppIcons.LockOpen)
                }
                else -> {
                    Text("Protect your account with a code from an authenticator app when signing in.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TonalButton("Set up 2FA", { vm.startSetup() }, Modifier.fillMaxWidth(), icon = AppIcons.Security)
                }
            }

            SectionHeader("This device", inset = false)
            FormCard {
                SwitchRow(
                    "Stay signed in", session.account.remembersPassword,
                    { on -> if (on) rememberDialog = true else vm.setRemember(null) },
                    "Keep your password encrypted on this device to renew sessions automatically",
                    enabled = tf?.enabled != true,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    vm.busy?.let { BusyDialog(it) }
    vm.backupCodes?.let { codes ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Save your backup codes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Each code works once if you lose your authenticator. They won't be shown again.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        codes.forEach { Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontFamily = FontFamily.Monospace) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.backupCodes = null }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { context.copyToClipboard("NPM backup codes", codes.joinToString("\n"), sensitive = true) }) { Text("Copy") } },
        )
    }
    codeDialog?.let { mode ->
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { codeDialog = null },
            title = { Text(if (mode == "disable") "Turn off 2FA" else "New backup codes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the current code from your authenticator app.")
                    FormTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, "6-digit code", keyboardType = KeyboardType.NumberPassword)
                }
            },
            confirmButton = {
                TextButton(onClick = { if (mode == "disable") vm.disable(code) else vm.regenerate(code); codeDialog = null }, enabled = code.length == 6) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { codeDialog = null }) { Text("Cancel") } },
        )
    }
    if (rememberDialog) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rememberDialog = false },
            title = { Text("Stay signed in") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your password. It's stored encrypted with a key that can't leave this device, and only used to renew your session.")
                    PasswordField(pw, { pw = it }, "Password")
                }
            },
            confirmButton = { TextButton(onClick = { vm.setRemember(pw); rememberDialog = false }, enabled = pw.isNotEmpty()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rememberDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TwoFactorSetupCard(vm: ProfileViewModel) {
    val setup = vm.setup ?: return
    val context = LocalContext.current
    var qr by remember(setup.otpauthUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(setup.otpauthUrl) { qr = withContext(Dispatchers.Default) { qrBitmap(setup.otpauthUrl) } }
    FormCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("1. Scan this code with your authenticator app, or open it directly on this phone.", style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                qr?.let { Image(it, "QR code for your authenticator app", Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, setup.otpauthUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { context.copyToClipboard("2FA secret", setup.secret, sensitive = true) }
            }) { Text("Open in authenticator app") }
            Text("Secret: ${setup.secret.chunked(4).joinToString(" ")}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.copyToClipboard("2FA secret", setup.secret, sensitive = true) }) { Text("Copy secret") }
            Text("2. Enter the 6-digit code it shows.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            FormTextField(vm.setupCode, { v -> vm.setupCode = v.filter { it.isDigit() }.take(6) }, "Code", keyboardType = KeyboardType.NumberPassword)
            PrimaryButton("Turn on 2FA", { vm.confirmSetup() }, enabled = vm.setupCode.length == 6)
            TextButton(onClick = { vm.cancelSetup() }) { Text("Cancel") }
        }
    }
}

/** Renders a QR code (black on white, 1 px per module; scaled up by the Image without smoothing). */
private fun qrBitmap(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
