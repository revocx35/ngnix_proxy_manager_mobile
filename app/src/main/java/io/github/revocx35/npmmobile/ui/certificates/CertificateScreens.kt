package io.github.revocx35.npmmobile.ui.certificates

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.Certificate
import io.github.revocx35.npmmobile.core.CertificateFiles
import io.github.revocx35.npmmobile.core.CertificateRequest
import io.github.revocx35.npmmobile.core.CertificateValidation
import io.github.revocx35.npmmobile.core.LetsEncryptOptions
import io.github.revocx35.npmmobile.core.NpmDates
import io.github.revocx35.npmmobile.core.NpmHost
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.core.Validation
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.ui.CustomCertRoute
import io.github.revocx35.npmmobile.ui.Format
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.UiError
import io.github.revocx35.npmmobile.ui.components.AppTopBar
import io.github.revocx35.npmmobile.ui.components.BusyDialog
import io.github.revocx35.npmmobile.ui.components.ConfirmDialog
import io.github.revocx35.npmmobile.ui.components.DomainNamesField
import io.github.revocx35.npmmobile.ui.components.ErrorCard
import io.github.revocx35.npmmobile.ui.components.FormCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.InfoRow
import io.github.revocx35.npmmobile.ui.components.LoadingBox
import io.github.revocx35.npmmobile.ui.components.NavRow
import io.github.revocx35.npmmobile.ui.components.PrimaryButton
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.StatusDot
import io.github.revocx35.npmmobile.ui.components.TonalButton
import io.github.revocx35.npmmobile.ui.expiryOf
import io.github.revocx35.npmmobile.ui.hosts.LetsEncryptOptionsForm
import io.github.revocx35.npmmobile.ui.hosts.SslForm
import io.github.revocx35.npmmobile.ui.hosts.editRoute
import io.github.revocx35.npmmobile.ui.hosts.icon
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.theme.LocalStatusColors
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------------------------ Details

class CertificateDetailViewModel(private val graph: AppGraph, val id: Int) : ViewModel() {
    var cert by mutableStateOf<Certificate?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<UiError?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
    var message by mutableStateOf<String?>(null)
    var deleted by mutableStateOf(false)
        private set

    init { load() }

    fun load() {
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                cert = graph.session.call { it.certificate(id) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadError = e.toUiError()
            } finally {
                loading = false
            }
        }
    }

    private fun act(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }

    fun renew() = act("Renewing with Let's Encrypt… This can take a minute.") {
        cert = graph.session.call { it.renewCertificate(id) }
        graph.repository.certificates.refresh()
        message = "Certificate renewed"
    }

    fun delete() = act("Deleting…") {
        graph.session.call { it.deleteCertificate(id) }
        graph.repository.certificates.load()
        deleted = true
    }

    fun download(context: Context, uri: Uri) = act("Downloading…") {
        val bytes = graph.session.call { it.downloadCertificate(id) }
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Can't write the file")
        }
        message = "Saved certificate files"
    }
}

@Composable
fun CertificateDetailScreen(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val user = LocalSession.current.user
    val canManage = user?.canManage(Section.Certificates) ?: true
    val vm: CertificateDetailViewModel = viewModel(key = "cert-$id") { CertificateDetailViewModel(graph, id) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.download(context, uri) }

    // Which hosts use this certificate (from the cached host lists).
    val repo = graph.repository
    val proxies by repo.proxyHostList.collectAsStateWithLifecycle()
    val redirs by repo.redirectionList.collectAsStateWithLifecycle()
    val deads by repo.deadHostList.collectAsStateWithLifecycle()
    val streams by repo.streamList.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (user?.canView(Section.ProxyHosts) != false) repo.proxyHosts.ensure()
        if (user?.canView(Section.RedirectionHosts) != false) repo.redirectionHosts.ensure()
        if (user?.canView(Section.DeadHosts) != false) repo.deadHosts.ensure()
        if (user?.canView(Section.Streams) != false) repo.streams.ensure()
    }
    val usedBy: List<NpmHost> = listOfNotNull(proxies.data, redirs.data, deads.data, streams.data).flatten().filter { it.certificateId == id }

    LaunchedEffect(vm.deleted) { if (vm.deleted) nav.popBackStack() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        topBar = { AppTopBar(vm.cert?.displayName ?: "Certificate", onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val cert = vm.cert
        when {
            vm.loading && cert == null -> LoadingBox(Modifier.padding(padding))
            cert == null -> Column(Modifier.padding(padding).padding(16.dp)) { ErrorCard(vm.loadError?.message ?: "Not found", onRetry = { vm.load() }) }
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                vm.error?.let { ErrorCard(it.message, Modifier.padding(horizontal = 16.dp), details = it.details) }
                val expiry = expiryOf(cert.expiresAt)
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Schedule, null, tint = expiry.color())
                    Spacer(Modifier.width(12.dp))
                    Text(if (cert.isIncomplete) "No certificate files uploaded yet" else expiry.text, style = MaterialTheme.typography.titleMedium, color = expiry.color())
                }
                FormCard(Modifier.padding(horizontal = 16.dp)) {
                    InfoRow("Domains", cert.domainNames.joinToString("\n").ifEmpty { "—" })
                    InfoRow("Provider", cert.providerLabel())
                    if (cert.niceName.isNotBlank()) InfoRow("Name", cert.niceName)
                    InfoRow("Valid until", Format.dateTime(cert.expiresAt))
                    InfoRow("Created", Format.dateTime(NpmDates.parse(cert.createdOn)))
                    cert.owner?.let { InfoRow("Owner", it.displayName) }
                }
                if (cert.isLetsEncrypt) {
                    Text(
                        "NPM renews Let's Encrypt certificates automatically before they expire.",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SectionHeader("Used by")
                if (usedBy.isEmpty()) {
                    Text("Not used by any host you can see.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    usedBy.forEach { h ->
                        NavRow(h.title, h.kind.icon(), onClick = { nav.navigate(h.kind.editRoute(h.id)) }, trailing = { StatusDot(h.status) })
                    }
                }
                if (canManage) {
                    SectionHeader("Actions")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cert.isLetsEncrypt) {
                            TonalButton("Renew now", { vm.renew() }, Modifier.fillMaxWidth(), icon = AppIcons.Renew)
                            TonalButton("Download files (.zip)", { saveZip.launch("certificate-${cert.id}.zip") }, Modifier.fillMaxWidth(), icon = AppIcons.Download)
                        } else {
                            TonalButton(if (cert.isIncomplete) "Upload certificate files" else "Replace certificate files", { nav.navigate(CustomCertRoute(cert.id)) }, Modifier.fillMaxWidth(), icon = AppIcons.UploadFile)
                        }
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(AppIcons.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete certificate", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    vm.busy?.let { BusyDialog(it) }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete certificate?",
            if (usedBy.isEmpty()) "\"${vm.cert?.displayName}\" will be deleted." else "${usedBy.size} host(s) use this certificate and will fall back to plain HTTP.",
            "Delete", onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

// ------------------------------------------------------------------------------------ New Let's Encrypt

class NewLetsEncryptViewModel(private val graph: AppGraph) : ViewModel() {
    var domains by mutableStateOf(listOf<String>())
    val options = SslForm().apply { certificate = io.github.revocx35.npmmobile.core.CertificateRef.RequestNew }
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    var showErrors by mutableStateOf(false)
        private set
    var reachability by mutableStateOf<Map<String, String>?>(null)
        private set
    var created by mutableStateOf<Certificate?>(null)
        private set

    val domainsError get() = if (domains.isEmpty()) "Add at least one domain" else domains.firstNotNullOfOrNull { Validation.domainError(it) }

    fun testReachability() {
        showErrors = true
        if (domainsError != null) return
        busy = "Asking NPM to check the domains…"
        error = null
        viewModelScope.launch {
            try {
                reachability = graph.session.call { it.testHttpReachability(domains) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }

    fun create() {
        showErrors = true
        if (domainsError != null || options.error() != null || busy != null) return
        if (!options.dnsChallenge && domains.any { it.startsWith("*") }) {
            error = UiError("Wildcard domains need a DNS challenge.")
            return
        }
        busy = "Requesting the certificate from Let's Encrypt… This can take a minute (longer with DNS propagation)."
        error = null
        viewModelScope.launch {
            try {
                val meta = LetsEncryptOptions(
                    dnsChallenge = options.dnsChallenge,
                    dnsProvider = options.dnsProvider.takeIf { options.dnsChallenge },
                    dnsProviderCredentials = options.dnsCredentials.takeIf { options.dnsChallenge },
                    propagationSeconds = options.propagation.toIntOrNull()?.takeIf { options.dnsChallenge && it > 0 },
                    keyType = options.keyType,
                )
                created = graph.session.call { it.createCertificate(CertificateRequest.letsEncrypt(domains, meta)) }
                graph.repository.certificates.refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }
}

private fun reachabilityText(result: String): Pair<Boolean, String> = when {
    result == "ok" -> true to "Reachable"
    result == "no-host" -> false to "No DNS record found"
    result == "failed" -> false to "Couldn't connect"
    result == "404" -> false to "Reached a server, but not this NPM (404)"
    result.startsWith("wrong-data") -> false to "Reached another server"
    result.startsWith("other:") -> false to result.removePrefix("other:")
    else -> false to result
}

@Composable
fun NewLetsEncryptScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val vm: NewLetsEncryptViewModel = viewModel { NewLetsEncryptViewModel(graph) }
    LaunchedEffect(vm.created) { vm.created?.let { nav.popBackStack() } }
    Scaffold(topBar = { AppTopBar("Let's Encrypt certificate", onBack = { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.error?.let { ErrorCard(it.message, details = it.details) }
            DomainNamesField(vm.domains, { vm.domains = it; }, error = if (vm.showErrors) vm.domainsError else null, modifier = Modifier.testTag("domains"))
            if (!vm.options.dnsChallenge) {
                TonalButton("Test HTTP reachability", { vm.testReachability() }, icon = AppIcons.Public)
                vm.reachability?.let { results ->
                    FormCard {
                        results.forEach { (domain, result) ->
                            val (ok, text) = reachabilityText(result)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (ok) AppIcons.CheckCircle else AppIcons.Error, null, tint = if (ok) LocalStatusColors.current.online else LocalStatusColors.current.offline, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(domain, style = MaterialTheme.typography.bodyLarge)
                                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            LetsEncryptOptionsForm(vm.options, vm.showErrors)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Request certificate", { vm.create() }, busy = vm.busy != null, icon = AppIcons.Certificate, modifier = Modifier.testTag("requestCert"))
            Spacer(Modifier.height(32.dp))
        }
    }
    vm.busy?.let { BusyDialog(it) }
}

// --------------------------------------------------------------------------------------- Custom upload

data class PickedFile(val name: String, val bytes: ByteArray)

class CustomCertificateViewModel(private val graph: AppGraph, val id: Int) : ViewModel() {
    var name by mutableStateOf("")
    var certificate by mutableStateOf<PickedFile?>(null)
        private set
    var key by mutableStateOf<PickedFile?>(null)
        private set
    var intermediate by mutableStateOf<PickedFile?>(null)
        private set
    var validation by mutableStateOf<CertificateValidation?>(null)
        private set
    var validationError by mutableStateOf<UiError?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    var showErrors by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set

    init {
        if (id != 0) viewModelScope.launch {
            runCatching { graph.session.call { it.certificate(id) } }.getOrNull()?.let { name = it.niceName }
        }
    }

    fun pick(context: Context, uri: Uri, slot: Int) {
        viewModelScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { readPicked(context, uri) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = UiError("Can't read that file: ${e.message}")
                return@launch
            }
            when (slot) {
                0 -> certificate = file
                1 -> key = file
                else -> intermediate = file
            }
            validate()
        }
    }

    fun clearIntermediate() {
        intermediate = null
        validate()
    }

    private fun files(): CertificateFiles? {
        val c = certificate ?: return null
        val k = key ?: return null
        return CertificateFiles(c.bytes, k.bytes, intermediate?.bytes)
    }

    private fun validate() {
        val f = files() ?: return
        validation = null
        validationError = null
        viewModelScope.launch {
            try {
                validation = graph.session.call { it.validateCertificate(f) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                validationError = e.toUiError()
            }
        }
    }

    val nameError get() = if (id == 0 && name.isBlank()) "Give the certificate a name" else null

    fun save() {
        showErrors = true
        val f = files()
        if (nameError != null || f == null || busy != null) return
        busy = "Uploading…"
        error = null
        viewModelScope.launch {
            var createdId: Int? = null
            try {
                val certId = if (id != 0) id else graph.session.call { it.createCertificate(CertificateRequest.custom(name.trim())) }.id.also { createdId = it }
                graph.session.call { it.uploadCertificate(certId, f) }
                graph.repository.certificates.refresh()
                done = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Don't leave an empty certificate behind when the upload of a new one failed.
                createdId?.let { cid -> runCatching { graph.session.call { it.deleteCertificate(cid) } } }
                error = e.toUiError()
            } finally {
                busy = null
            }
        }
    }

    private fun readPicked(context: Context, uri: Uri): PickedFile {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= 1_000_000) { "File is larger than 1 MB, not a PEM certificate" }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: error("Can't open the file")
        return PickedFile(name, bytes)
    }
}

@Composable
fun CustomCertificateScreen(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val vm: CustomCertificateViewModel = viewModel(key = "custom-$id") { CustomCertificateViewModel(graph, id) }
    val pickCert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.pick(context, u, 0) } }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.pick(context, u, 1) } }
    val pickInter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.pick(context, u, 2) } }
    LaunchedEffect(vm.done) { if (vm.done) nav.popBackStack() }

    Scaffold(topBar = { AppTopBar(if (id == 0) "Custom certificate" else "Replace certificate files", onBack = { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.error?.let { ErrorCard(it.message, details = it.details) }
            if (id == 0) FormTextField(vm.name, { vm.name = it }, "Name", error = if (vm.showErrors) vm.nameError else null, placeholder = "My wildcard certificate")
            InfoBanner("Pick PEM files (.pem, .crt, .key). The private key is sent to your NPM server only.", icon = AppIcons.Key)
            FileSlot("Certificate", vm.certificate, required = true, showErrors = vm.showErrors) { pickCert.launch(arrayOf("*/*")) }
            FileSlot("Private key", vm.key, required = true, showErrors = vm.showErrors) { pickKey.launch(arrayOf("*/*")) }
            FileSlot("Intermediate certificate (optional)", vm.intermediate, required = false, showErrors = false, onClear = if (vm.intermediate != null) ({ vm.clearIntermediate() }) else null) { pickInter.launch(arrayOf("*/*")) }
            vm.validation?.certificate?.let { info ->
                FormCard {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.CheckCircle, null, tint = LocalStatusColors.current.online)
                        Spacer(Modifier.width(8.dp))
                        Text(if (vm.validation?.certificateKey == true) "Certificate and key match" else "Certificate is valid", style = MaterialTheme.typography.titleSmall)
                    }
                    InfoRow("Common name", info.cn)
                    InfoRow("Issuer", info.issuer)
                    info.dates?.let { d -> InfoRow("Valid", "${Format.date(java.time.Instant.ofEpochSecond(d.from))} – ${Format.date(java.time.Instant.ofEpochSecond(d.to))}") }
                }
            }
            vm.validationError?.let { ErrorCard("These files don't form a valid certificate: ${it.message}", details = it.details) }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (id == 0) "Save certificate" else "Upload files", { vm.save() }, busy = vm.busy != null, enabled = vm.certificate != null && vm.key != null)
            Spacer(Modifier.height(32.dp))
        }
    }
    vm.busy?.let { BusyDialog(it) }
}

@Composable
private fun FileSlot(label: String, file: PickedFile?, required: Boolean, showErrors: Boolean, onClear: (() -> Unit)? = null, onPick: () -> Unit) {
    FormCard {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (file != null) AppIcons.CheckCircle else AppIcons.UploadFile, null, tint = if (file != null) LocalStatusColors.current.online else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    file?.let { "${it.name} · ${it.bytes.size} bytes" } ?: if (required && showErrors) "Required" else "No file chosen",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (file == null && required && showErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClear != null) TextButton(onClick = onClear) { Text("Remove") }
            TextButton(onClick = onPick) { Text(if (file == null) "Choose" else "Change") }
        }
    }
}
