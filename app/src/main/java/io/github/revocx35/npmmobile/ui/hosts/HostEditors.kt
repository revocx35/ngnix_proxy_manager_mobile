package io.github.revocx35.npmmobile.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.revocx35.npmmobile.core.CertificateRef
import io.github.revocx35.npmmobile.core.DeadHost
import io.github.revocx35.npmmobile.core.DeadHostRequest
import io.github.revocx35.npmmobile.core.HostKind
import io.github.revocx35.npmmobile.core.LocationRequest
import io.github.revocx35.npmmobile.core.NpmClient
import io.github.revocx35.npmmobile.core.ProxyHost
import io.github.revocx35.npmmobile.core.ProxyHostRequest
import io.github.revocx35.npmmobile.core.RedirectionHost
import io.github.revocx35.npmmobile.core.RedirectionHostRequest
import io.github.revocx35.npmmobile.core.Stream
import io.github.revocx35.npmmobile.core.StreamRequest
import io.github.revocx35.npmmobile.core.Validation
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.components.DomainNamesField
import io.github.revocx35.npmmobile.ui.components.DropdownField
import io.github.revocx35.npmmobile.ui.components.FormCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.NumberField
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.SegmentedPicker
import io.github.revocx35.npmmobile.ui.components.SwitchRow
import io.github.revocx35.npmmobile.ui.components.TonalButton
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import java.util.UUID

private fun domainsError(domains: List<String>): String? =
    if (domains.isEmpty()) "Add at least one domain" else domains.firstNotNullOfOrNull { Validation.domainError(it) }

@Composable
private fun AdvancedConfigField(value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    InfoBanner("Custom nginx directives added to this host's server block. A mistake here can take the host offline; NPM then shows the nginx error.", icon = AppIcons.Code)
    FormTextField(
        value, onChange, "Custom nginx configuration", singleLine = false, minLines = 8, mono = true, enabled = enabled,
        placeholder = "location /health {\n    return 200;\n}",
        modifier = Modifier.testTag("advanced"),
    )
}

// ---------------------------------------------------------------------------------------------- Proxy host

class LocationForm(val key: String = UUID.randomUUID().toString()) {
    var path by mutableStateOf("/")
    var scheme by mutableStateOf("http")
    var host by mutableStateOf("")
    var port by mutableStateOf("80")
    var advanced by mutableStateOf("")

    val pathError get() = if (!path.startsWith("/")) "Must start with /" else null
    val hostError get() = Validation.forwardHostError(host, allowPath = true)
    val portError get() = Validation.portError(port.toIntOrNull())
    val valid get() = pathError == null && hostError == null && portError == null
    fun snapshot() = "$path|$scheme|$host|$port|$advanced"
}

class ProxyHostEditorViewModel(graph: AppGraph, id: Int) : HostEditorViewModel<ProxyHost>(graph, HostKind.Proxy, id) {
    var domains by mutableStateOf(listOf<String>())
    var scheme by mutableStateOf("http")
    var forwardHost by mutableStateOf("")
    var forwardPort by mutableStateOf("")
    var caching by mutableStateOf(false)
    var blockExploits by mutableStateOf(true)
    var websockets by mutableStateOf(true)
    var accessListId by mutableIntStateOf(0)
    val locations = mutableStateListOf<LocationForm>()
    val ssl = SslForm()
    var advanced by mutableStateOf("")

    init { start() }

    override suspend fun fetch(client: NpmClient, id: Int) = client.proxyHost(id)

    override fun fill(host: ProxyHost) {
        domains = host.domainNames
        scheme = host.forwardScheme
        forwardHost = host.forwardHost
        forwardPort = host.forwardPort.toString()
        caching = host.cachingEnabled
        blockExploits = host.blockExploits
        websockets = host.allowWebsocketUpgrade
        accessListId = host.accessListId
        locations.clear()
        host.locations.forEach { l ->
            locations += LocationForm().apply {
                path = l.path
                scheme = l.forwardScheme
                // NPM splits "host/path" into forward_host + forward_path; show them joined again.
                this.host = l.forwardHost + (l.forwardPath?.takeIf { it.isNotBlank() && it != "/" }.orEmpty())
                port = l.forwardPort.toString()
                advanced = l.advancedConfig.orEmpty()
            }
        }
        ssl.fill(host.certificateId, host.sslForced, host.http2Support, host.hstsEnabled, host.hstsSubdomains, host.trustForwardedProto, host.meta)
        advanced = host.advancedConfig
    }

    val domainsError get() = domainsError(domains)
    val hostError get() = Validation.forwardHostError(forwardHost)
    val portError get() = Validation.portError(forwardPort.toIntOrNull())

    override fun firstInvalidTab(): Int? = when {
        domainsError != null || hostError != null || portError != null -> 0
        locations.any { !it.valid } -> 1
        ssl.error() != null -> 2
        else -> null
    }

    override val requestsCertificate get() = ssl.certificate == CertificateRef.RequestNew

    override fun snapshot() = listOf(domains, scheme, forwardHost, forwardPort, caching, blockExploits, websockets, accessListId, advanced, ssl.snapshot(), locations.joinToString { it.snapshot() }).joinToString("§")

    private fun request() = ProxyHostRequest(
        domainNames = domains,
        forwardScheme = scheme,
        forwardHost = forwardHost.trim(),
        forwardPort = forwardPort.toInt(),
        certificateId = ssl.certificate,
        sslForced = ssl.effectiveForced,
        hstsEnabled = ssl.effectiveHsts,
        hstsSubdomains = ssl.effectiveHstsSub,
        trustForwardedProto = ssl.trustForwardedProto,
        http2Support = ssl.effectiveHttp2,
        blockExploits = blockExploits,
        cachingEnabled = caching,
        allowWebsocketUpgrade = websockets,
        accessListId = accessListId,
        advancedConfig = advanced,
        locations = locations.map { LocationRequest(it.path.trim(), it.scheme, it.host.trim(), it.port.toInt(), it.advanced) },
        meta = ssl.letsEncryptMeta(),
    )

    override suspend fun persist(client: NpmClient): ProxyHost =
        if (isNew) client.createProxyHost(request()) else client.updateProxyHost(id, request())
}

@Composable
fun ProxyHostEditor(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val vm: ProxyHostEditorViewModel = viewModel(key = "proxy-$id") { ProxyHostEditorViewModel(graph, id) }
    val acls by graph.repository.accessListList.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { graph.repository.accessLists.ensure() }
    val err = vm.showErrors

    HostEditorScaffold(nav, vm, listOf("Details", "Locations", "SSL", "Advanced"), openDomain = vm.domains.firstOrNull { !it.startsWith("*") }) { tab ->
        when (tab) {
            0 -> {
                DomainNamesField(vm.domains, { vm.domains = it }, error = if (err) vm.domainsError else null, modifier = Modifier.testTag("domains"))
                SectionHeader("Forward to", inset = false)
                SegmentedPicker(listOf("http" to "http", "https" to "https"), vm.scheme, { vm.scheme = it })
                FormTextField(vm.forwardHost, { vm.forwardHost = it }, "Hostname or IP", error = if (err) vm.hostError else null, placeholder = "192.168.1.20 or my-container", modifier = Modifier.testTag("forwardHost"))
                NumberField(vm.forwardPort, { vm.forwardPort = it }, "Port", error = if (err) vm.portError else null, modifier = Modifier.testTag("forwardPort"))
                FormCard {
                    SwitchRow("Cache assets", vm.caching, { vm.caching = it })
                    SwitchRow("Block common exploits", vm.blockExploits, { vm.blockExploits = it })
                    SwitchRow("Websockets support", vm.websockets, { vm.websockets = it })
                }
                val options = listOf(Triple(0, "Publicly accessible", "No access list")) +
                    acls.data.orEmpty().map { Triple(it.id, it.name, "${it.items.size} users · ${it.clients.size} rules") } +
                    (if (vm.accessListId != 0 && acls.data?.none { it.id == vm.accessListId } == true) listOf(Triple(vm.accessListId, "Access list #${vm.accessListId}", null)) else emptyList())
                DropdownField("Access list", options, vm.accessListId, { vm.accessListId = it }, leadingIcon = AppIcons.AccessList)
            }
            1 -> {
                InfoBanner("Custom locations forward a path (like /api) to a different backend. The target may include a path: 10.0.0.5/app.", icon = AppIcons.Route)
                vm.locations.forEachIndexed { index, loc ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Location ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.locations.remove(loc) }) { Icon(AppIcons.Delete, "Remove location") }
                            }
                            FormTextField(loc.path, { loc.path = it }, "Path", error = if (err) loc.pathError else null, placeholder = "/api")
                            SegmentedPicker(listOf("http" to "http", "https" to "https"), loc.scheme, { loc.scheme = it })
                            FormTextField(loc.host, { loc.host = it }, "Hostname or IP (optionally /path)", error = if (err) loc.hostError else null)
                            NumberField(loc.port, { loc.port = it }, "Port", error = if (err) loc.portError else null)
                            FormTextField(loc.advanced, { loc.advanced = it }, "Custom configuration (optional)", singleLine = false, minLines = 2, mono = true)
                        }
                    }
                }
                TonalButton("Add location", { vm.locations += LocationForm() }, icon = AppIcons.Add, modifier = Modifier.fillMaxWidth())
            }
            2 -> SslSection(vm.ssl, vm.domains, err, showTrustForwarded = true)
            else -> AdvancedConfigField(vm.advanced, { vm.advanced = it })
        }
    }
}

// ---------------------------------------------------------------------------------------- Redirection host

val REDIRECT_CODES = listOf(
    Triple(301, "301 Moved permanently", "Browsers and search engines remember it"),
    Triple(302, "302 Found", "Temporary"),
    Triple(303, "303 See other", "Temporary, always GET"),
    Triple(307, "307 Temporary redirect", "Keeps the request method"),
    Triple(308, "308 Permanent redirect", "Permanent, keeps the request method"),
    Triple(300, "300 Multiple choices", null),
)

class RedirectionEditorViewModel(graph: AppGraph, id: Int) : HostEditorViewModel<RedirectionHost>(graph, HostKind.Redirection, id) {
    var domains by mutableStateOf(listOf<String>())
    var scheme by mutableStateOf("auto")
    var target by mutableStateOf("")
    var code by mutableIntStateOf(301)
    var preservePath by mutableStateOf(true)
    var blockExploits by mutableStateOf(true)
    val ssl = SslForm()
    var advanced by mutableStateOf("")

    init { start() }

    override suspend fun fetch(client: NpmClient, id: Int) = client.redirectionHost(id)

    override fun fill(host: RedirectionHost) {
        domains = host.domainNames
        scheme = host.forwardScheme
        target = host.forwardDomainName
        code = host.forwardHttpCode
        preservePath = host.preservePath
        blockExploits = host.blockExploits
        ssl.fill(host.certificateId, host.sslForced, host.http2Support, host.hstsEnabled, host.hstsSubdomains, meta = host.meta)
        advanced = host.advancedConfig
    }

    val domainsError get() = domainsError(domains)
    val targetError get() = Validation.redirectTargetError(target)

    override fun firstInvalidTab(): Int? = when {
        domainsError != null || targetError != null -> 0
        ssl.error() != null -> 1
        else -> null
    }

    override val requestsCertificate get() = ssl.certificate == CertificateRef.RequestNew
    override fun snapshot() = listOf(domains, scheme, target, code, preservePath, blockExploits, advanced, ssl.snapshot()).joinToString("§")

    private fun request() = RedirectionHostRequest(
        domainNames = domains, forwardHttpCode = code, forwardScheme = scheme, forwardDomainName = target.trim(),
        preservePath = preservePath, certificateId = ssl.certificate, sslForced = ssl.effectiveForced,
        hstsEnabled = ssl.effectiveHsts, hstsSubdomains = ssl.effectiveHstsSub, http2Support = ssl.effectiveHttp2,
        blockExploits = blockExploits, advancedConfig = advanced, meta = ssl.letsEncryptMeta(),
    )

    override suspend fun persist(client: NpmClient) =
        if (isNew) client.createRedirectionHost(request()) else client.updateRedirectionHost(id, request())
}

@Composable
fun RedirectionHostEditor(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val vm: RedirectionEditorViewModel = viewModel(key = "redir-$id") { RedirectionEditorViewModel(graph, id) }
    val err = vm.showErrors
    HostEditorScaffold(nav, vm, listOf("Details", "SSL", "Advanced"), openDomain = vm.domains.firstOrNull { !it.startsWith("*") }) { tab ->
        when (tab) {
            0 -> {
                DomainNamesField(vm.domains, { vm.domains = it }, error = if (err) vm.domainsError else null, modifier = Modifier.testTag("domains"))
                SectionHeader("Redirect to", inset = false)
                SegmentedPicker(listOf("auto" to "Same scheme", "http" to "http", "https" to "https"), vm.scheme, { vm.scheme = it })
                FormTextField(vm.target, { vm.target = it }, "Domain (optionally with a path)", error = if (err) vm.targetError else null, placeholder = "new.example.com", modifier = Modifier.testTag("target"))
                DropdownField("HTTP code", REDIRECT_CODES, vm.code, { vm.code = it })
                FormCard {
                    SwitchRow("Preserve path", vm.preservePath, { vm.preservePath = it }, "/a/b on the old domain goes to /a/b on the new one")
                    SwitchRow("Block common exploits", vm.blockExploits, { vm.blockExploits = it })
                }
            }
            1 -> SslSection(vm.ssl, vm.domains, err)
            else -> AdvancedConfigField(vm.advanced, { vm.advanced = it })
        }
    }
}

// ------------------------------------------------------------------------------------------------ 404 host

class DeadHostEditorViewModel(graph: AppGraph, id: Int) : HostEditorViewModel<DeadHost>(graph, HostKind.Dead, id) {
    var domains by mutableStateOf(listOf<String>())
    val ssl = SslForm()
    var advanced by mutableStateOf("")

    init { start() }

    override suspend fun fetch(client: NpmClient, id: Int) = client.deadHost(id)

    override fun fill(host: DeadHost) {
        domains = host.domainNames
        ssl.fill(host.certificateId, host.sslForced, host.http2Support, host.hstsEnabled, host.hstsSubdomains, meta = host.meta)
        advanced = host.advancedConfig
    }

    val domainsError get() = domainsError(domains)

    override fun firstInvalidTab(): Int? = when {
        domainsError != null -> 0
        ssl.error() != null -> 1
        else -> null
    }

    override val requestsCertificate get() = ssl.certificate == CertificateRef.RequestNew
    override fun snapshot() = listOf(domains, advanced, ssl.snapshot()).joinToString("§")

    private fun request() = DeadHostRequest(
        domainNames = domains, certificateId = ssl.certificate, sslForced = ssl.effectiveForced, hstsEnabled = ssl.effectiveHsts,
        hstsSubdomains = ssl.effectiveHstsSub, http2Support = ssl.effectiveHttp2, advancedConfig = advanced, meta = ssl.letsEncryptMeta(),
    )

    override suspend fun persist(client: NpmClient) = if (isNew) client.createDeadHost(request()) else client.updateDeadHost(id, request())
}

@Composable
fun DeadHostEditor(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val vm: DeadHostEditorViewModel = viewModel(key = "dead-$id") { DeadHostEditorViewModel(graph, id) }
    HostEditorScaffold(nav, vm, listOf("Details", "SSL", "Advanced"), openDomain = vm.domains.firstOrNull { !it.startsWith("*") }) { tab ->
        when (tab) {
            0 -> {
                InfoBanner("A 404 host answers every request for these domains with \"404 Not Found\", e.g. to park a domain.", icon = AppIcons.Dead)
                DomainNamesField(vm.domains, { vm.domains = it }, error = if (vm.showErrors) vm.domainsError else null, modifier = Modifier.testTag("domains"))
            }
            1 -> SslSection(vm.ssl, vm.domains, vm.showErrors)
            else -> AdvancedConfigField(vm.advanced, { vm.advanced = it })
        }
    }
}

// -------------------------------------------------------------------------------------------------- Stream

class StreamEditorViewModel(graph: AppGraph, id: Int) : HostEditorViewModel<Stream>(graph, HostKind.Stream, id) {
    var incomingPort by mutableStateOf("")
    var forwardHost by mutableStateOf("")
    var forwardPort by mutableStateOf("")
    var tcp by mutableStateOf(true)
    var udp by mutableStateOf(false)
    val ssl = SslForm()

    init { start() }

    override suspend fun fetch(client: NpmClient, id: Int) = client.stream(id)

    override fun fill(host: Stream) {
        incomingPort = host.incomingPort.toString()
        forwardHost = host.forwardingHost
        forwardPort = host.forwardingPort.toString()
        tcp = host.tcpForwarding
        udp = host.udpForwarding
        ssl.fill(host.certificateId, false, false, false, false)
    }

    val incomingError get() = Validation.portError(incomingPort.toIntOrNull())
    val hostError get() = Validation.forwardHostError(forwardHost)
    val portError get() = Validation.portError(forwardPort.toIntOrNull())
    val protocolError get() = if (!tcp && !udp) "Choose TCP, UDP or both" else null

    override fun firstInvalidTab(): Int? = if (listOf(incomingError, hostError, portError, protocolError).any { it != null }) 0 else null
    override fun snapshot() = listOf(incomingPort, forwardHost, forwardPort, tcp, udp, ssl.certificate).joinToString("§")

    private fun request() = StreamRequest(
        incomingPort = incomingPort.toInt(), forwardingHost = forwardHost.trim(), forwardingPort = forwardPort.toInt(),
        tcpForwarding = tcp, udpForwarding = udp,
        // Certificates only apply to TCP streams (TLS termination); never request a new one from here.
        certificateId = if (tcp && ssl.certificate is CertificateRef.Existing) ssl.certificate else CertificateRef.None,
    )

    override suspend fun persist(client: NpmClient) = if (isNew) client.createStream(request()) else client.updateStream(id, request())
}

@Composable
fun StreamEditor(nav: NavHostController, id: Int) {
    val graph = LocalGraph.current
    val vm: StreamEditorViewModel = viewModel(key = "stream-$id") { StreamEditorViewModel(graph, id) }
    val err = vm.showErrors
    HostEditorScaffold(nav, vm, listOf("Details")) {
        InfoBanner("Streams forward raw TCP/UDP traffic arriving on a port of the NPM server (e.g. SSH, databases, game servers). The port must be published by the NPM container.", icon = AppIcons.Stream)
        NumberField(vm.incomingPort, { vm.incomingPort = it }, "Incoming port", error = if (err) vm.incomingError else null, modifier = Modifier.testTag("incomingPort"))
        SectionHeader("Forward to", inset = false)
        FormTextField(vm.forwardHost, { vm.forwardHost = it }, "Hostname or IP", error = if (err) vm.hostError else null, modifier = Modifier.testTag("forwardHost"))
        NumberField(vm.forwardPort, { vm.forwardPort = it }, "Port", error = if (err) vm.portError else null, modifier = Modifier.testTag("forwardPort"))
        FormCard {
            SwitchRow("TCP forwarding", vm.tcp, { vm.tcp = it })
            SwitchRow("UDP forwarding", vm.udp, { vm.udp = it })
        }
        if (err && vm.protocolError != null) Text(vm.protocolError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (vm.tcp) {
            SectionHeader("TLS (optional)", inset = false)
            SslSection(vm.ssl, emptyList(), err, allowRequestNew = false, onlyCertificate = true)
        }
    }
}
