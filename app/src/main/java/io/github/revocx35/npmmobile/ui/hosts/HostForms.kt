package io.github.revocx35.npmmobile.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.revocx35.npmmobile.core.Certificate
import io.github.revocx35.npmmobile.core.CertificateRef
import io.github.revocx35.npmmobile.core.DnsProvider
import io.github.revocx35.npmmobile.core.LetsEncryptOptions
import io.github.revocx35.npmmobile.core.NpmHost
import io.github.revocx35.npmmobile.core.Section
import io.github.revocx35.npmmobile.core.string
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.ui.LocalGraph
import io.github.revocx35.npmmobile.ui.LocalSession
import io.github.revocx35.npmmobile.ui.components.DropdownField
import io.github.revocx35.npmmobile.ui.components.FormCard
import io.github.revocx35.npmmobile.ui.components.FormTextField
import io.github.revocx35.npmmobile.ui.components.InfoBanner
import io.github.revocx35.npmmobile.ui.components.NumberField
import io.github.revocx35.npmmobile.ui.components.SectionHeader
import io.github.revocx35.npmmobile.ui.components.SegmentedPicker
import io.github.revocx35.npmmobile.ui.components.SwitchRow
import io.github.revocx35.npmmobile.ui.expiryOf
import io.github.revocx35.npmmobile.ui.theme.AppIcons
import io.github.revocx35.npmmobile.ui.toUiError
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Enable / disable / delete from lists and editors. */
class HostActionsViewModel(private val graph: AppGraph) : ViewModel() {
    var busy by mutableStateOf(setOf<Pair<io.github.revocx35.npmmobile.core.HostKind, Int>>())
        private set
    var message by mutableStateOf<String?>(null)

    fun setEnabled(host: NpmHost, enabled: Boolean, after: () -> Unit = {}) = run(host, if (enabled) "Enabled" else "Disabled", after) {
        graph.session.call { c -> if (enabled) c.enableHost(host.kind, host.id) else c.disableHost(host.kind, host.id) }
    }

    fun delete(host: NpmHost, after: () -> Unit = {}) = run(host, "Deleted", after) {
        graph.session.call { it.deleteHost(host.kind, host.id) }
    }

    private fun run(host: NpmHost, done: String, after: () -> Unit, block: suspend () -> Unit) {
        val key = host.kind to host.id
        if (key in busy) return
        busy = busy + key
        viewModelScope.launch {
            try {
                block()
                message = "$done ${host.title}"
                after()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = e.toUiError().message
            } finally {
                graph.repository.hostsChanged(host.kind)
                busy = busy - key
            }
        }
    }
}

/** True if a certificate for [certDomains] is valid for [domain] (exact or one-level wildcard). */
fun covers(certDomains: List<String>, domain: String): Boolean = certDomains.any { c ->
    c.equals(domain, ignoreCase = true) ||
        (c.startsWith("*.") && domain.substringAfter('.', "").equals(c.removePrefix("*."), ignoreCase = true) && !domain.startsWith("*"))
}

/** The SSL part of a host form, shared by proxy, redirection and 404 hosts. */
class SslForm {
    var certificate by mutableStateOf<CertificateRef>(CertificateRef.None)
    var sslForced by mutableStateOf(false)
    var http2 by mutableStateOf(false)
    var hsts by mutableStateOf(false)
    var hstsSubdomains by mutableStateOf(false)
    var trustForwardedProto by mutableStateOf(false)

    // Only used with CertificateRef.RequestNew
    var dnsChallenge by mutableStateOf(false)
    var dnsProvider by mutableStateOf<String?>(null)
    var dnsCredentials by mutableStateOf("")
    var propagation by mutableStateOf("")
    var keyType by mutableStateOf(LetsEncryptOptions.KEY_ECDSA)

    val hasCertificate: Boolean get() = certificate != CertificateRef.None

    fun fill(certificateId: Int, sslForced: Boolean, http2: Boolean, hsts: Boolean, hstsSubdomains: Boolean, trustForwardedProto: Boolean = false, meta: JsonObject? = null) {
        certificate = CertificateRef.of(certificateId)
        this.sslForced = sslForced
        this.http2 = http2
        this.hsts = hsts
        this.hstsSubdomains = hstsSubdomains
        this.trustForwardedProto = trustForwardedProto
        meta?.let {
            dnsChallenge = (it["dns_challenge"] as? JsonPrimitive)?.content == "true"
            dnsProvider = it.string("dns_provider")
        }
    }

    /** Let's Encrypt options, only when a new certificate should be requested with the host. */
    fun letsEncryptMeta(): JsonObject? = if (certificate != CertificateRef.RequestNew) null else LetsEncryptOptions(
        dnsChallenge = dnsChallenge,
        dnsProvider = dnsProvider.takeIf { dnsChallenge },
        dnsProviderCredentials = dnsCredentials.takeIf { dnsChallenge },
        propagationSeconds = propagation.toIntOrNull()?.takeIf { dnsChallenge && it > 0 },
        keyType = keyType,
    ).toMeta()

    fun error(): String? = when {
        certificate != CertificateRef.RequestNew || !dnsChallenge -> null
        dnsProvider == null -> "Choose a DNS provider"
        dnsCredentials.isBlank() -> "Enter the DNS provider credentials"
        else -> null
    }

    fun snapshot() = listOf(certificate, sslForced, http2, hsts, hstsSubdomains, trustForwardedProto, dnsChallenge, dnsProvider, dnsCredentials, propagation, keyType).joinToString("|")

    // SSL options without a certificate make no sense; NPM ignores them, so send false.
    val effectiveForced get() = hasCertificate && sslForced
    val effectiveHttp2 get() = hasCertificate && http2
    val effectiveHsts get() = hasCertificate && sslForced && hsts
    val effectiveHstsSub get() = effectiveHsts && hstsSubdomains
}

@Composable
fun SslSection(form: SslForm, domains: List<String>, showErrors: Boolean, showTrustForwarded: Boolean = false, allowRequestNew: Boolean = true, onlyCertificate: Boolean = false) {
    val graph = LocalGraph.current
    val user = LocalSession.current.user
    val certs by graph.repository.certificateList.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { graph.repository.certificates.ensure() }
    val canRequest = allowRequestNew && (user?.canManage(Section.Certificates) ?: true)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val sorted = certs.data.orEmpty().filter { !it.isIncomplete }.sortedWith(
            compareByDescending<Certificate> { c -> domains.isNotEmpty() && domains.all { covers(c.domainNames, it) } }.thenBy { it.displayName },
        )
        val options = buildList {
            add(Triple<CertificateRef, String, String?>(CertificateRef.None, "None", "Plain HTTP only"))
            if (canRequest) add(Triple(CertificateRef.RequestNew, "Request a new certificate", "Let's Encrypt, for the domains above"))
            sorted.forEach { c ->
                add(Triple(CertificateRef.Existing(c.id), c.displayName, (if (c.isLetsEncrypt) "Let's Encrypt" else "Custom") + " · " + expiryOf(c.expiresAt).text))
            }
            // A certificate we can't see (another user's) stays selectable as is.
            val cur = form.certificate
            if (cur is CertificateRef.Existing && sorted.none { it.id == cur.id }) add(Triple(cur, "Certificate #${cur.id}", null))
        }
        DropdownField("SSL certificate", options, form.certificate, { form.certificate = it }, leadingIcon = AppIcons.Certificate)
        val selected = (form.certificate as? CertificateRef.Existing)?.let { ref -> certs.data?.firstOrNull { it.id == ref.id } }
        if (selected != null) {
            val uncovered = domains.filterNot { covers(selected.domainNames, it) }
            if (uncovered.isNotEmpty()) InfoBanner("This certificate doesn't cover ${uncovered.joinToString(", ")}: browsers will show a warning there.", warning = true, icon = AppIcons.Warning)
        }
        if (form.certificate == CertificateRef.RequestNew) {
            LetsEncryptOptionsForm(form, showErrors)
        }
        if (!onlyCertificate) {
            FormCard {
                SwitchRow("Force SSL", form.sslForced, { form.sslForced = it }, "Redirect HTTP to HTTPS", enabled = form.hasCertificate)
                SwitchRow("HTTP/2 support", form.http2, { form.http2 = it }, enabled = form.hasCertificate)
                SwitchRow("HSTS", form.hsts, { form.hsts = it }, "Tell browsers to always use HTTPS", enabled = form.hasCertificate && form.sslForced)
                SwitchRow("HSTS for subdomains", form.hstsSubdomains, { form.hstsSubdomains = it }, enabled = form.hasCertificate && form.sslForced && form.hsts)
                if (showTrustForwarded) {
                    SwitchRow("Trust upstream forwarded proto", form.trustForwardedProto, { form.trustForwardedProto = it }, "Use X-Forwarded-Proto from a proxy in front of NPM")
                }
            }
        }
    }
}

@Composable
fun LetsEncryptOptionsForm(form: SslForm, showErrors: Boolean) {
    val graph = LocalGraph.current
    val session = LocalSession.current
    val providers by graph.repository.dnsProviders.state.collectAsStateWithLifecycle()
    LaunchedEffect(form.dnsChallenge) { if (form.dnsChallenge) graph.repository.dnsProviders.ensure() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoBanner("Let's Encrypt will use your NPM account e-mail (${session.user?.email ?: session.account.email}). The domains must point to this server, or use a DNS challenge.")
        FormCard {
            SwitchRow("Use a DNS challenge", form.dnsChallenge, { form.dnsChallenge = it }, "Needed for wildcard domains or servers not reachable on port 80", icon = AppIcons.Cloud)
        }
        if (form.dnsChallenge) {
            val list: List<DnsProvider> = providers.data.orEmpty()
            DropdownField(
                "DNS provider",
                list.map { Triple<String?, String, String?>(it.id, it.name, null) },
                form.dnsProvider,
                { id ->
                    val template = list.firstOrNull { it.id == id }?.credentials.orEmpty()
                    // Pre-fill the credentials file with the provider's template (unless the user typed something).
                    if (form.dnsCredentials.isBlank() || list.any { it.credentials == form.dnsCredentials }) form.dnsCredentials = template
                    form.dnsProvider = id
                },
                supportingText = when {
                    providers.loading && list.isEmpty() -> "Loading providers…"
                    providers.error != null -> providers.error
                    showErrors && form.dnsProvider == null -> "Choose a DNS provider"
                    else -> null
                },
            )
            FormTextField(
                form.dnsCredentials, { form.dnsCredentials = it }, "Credentials file content",
                singleLine = false, minLines = 3, mono = true,
                error = if (showErrors && form.dnsCredentials.isBlank()) "Enter the credentials" else null,
                supportingText = "Stored on the NPM server in plain text; use a token limited to DNS edits.",
            )
            NumberField(form.propagation, { form.propagation = it }, "Propagation seconds (optional)", supportingText = "Leave empty for the provider's default")
        }
        Column(Modifier.padding(top = 4.dp)) {
            SectionHeader("Key type", inset = false)
            SegmentedPicker(listOf(LetsEncryptOptions.KEY_ECDSA to "ECDSA", LetsEncryptOptions.KEY_RSA to "RSA"), form.keyType, { form.keyType = it })
        }
    }
}
