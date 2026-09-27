package io.github.revocx35.npmmobile.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The admin address of an NPM instance, normalized from what the user typed:
 * `npm.example.com` -> `https://npm.example.com`, `http://10.0.0.2:81/api/` -> `http://10.0.0.2:81`.
 * Sub-path installs (`https://example.com/npm`) keep their path.
 */
data class ServerAddress(val baseUrl: HttpUrl) {
    /** The `/api/` root every endpoint is resolved against. */
    val apiUrl: HttpUrl = baseUrl.newBuilder().addPathSegment("api").addPathSegment("").build()

    val display: String get() = baseUrl.toString().removeSuffix("/")
    val isHttps: Boolean get() = baseUrl.isHttps

    /** True for loopback, RFC 1918, link-local and `.local`/`.lan`/`.home.arpa` names: plain HTTP is usual there. */
    val isLocalNetwork: Boolean get() = isLocalHost(baseUrl.host)

    fun resolve(path: String): HttpUrl = apiUrl.resolve(path.trimStart('/')) ?: error("Bad API path: $path")

    override fun toString() = display

    companion object {
        fun parse(input: String): ServerAddress? {
            var text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            if (!text.contains("://")) text = "https://$text"
            val url = text.toHttpUrlOrNull() ?: return null
            if (url.host.isEmpty()) return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            // People paste the address of the page they're on: drop the API root and known UI routes.
            val cut = segments.indexOfFirst { it == "api" || it in UI_ROUTES }
            if (cut >= 0) while (segments.size > cut) segments.removeAt(segments.size - 1)
            val base = url.newBuilder().query(null).fragment(null).encodedPath("/").apply {
                segments.forEach { addPathSegment(it) }
            }.build()
            return ServerAddress(base)
        }

        // First path segments of the NPM web UI routes (`/nginx/proxy`, `/login`, …).
        private val UI_ROUTES = setOf("login", "setup", "nginx", "dashboard", "users", "audit-log", "settings", "certificates", "access")

        fun isLocalHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
            val isV6Literal = h.contains(':') && h.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' }
            if (!isV6Literal && !Validation.isIpv4(h)) return false
            // Only literal IP addresses reach this point, so no DNS lookup happens here.
            val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
            return addr.isLoopbackAddress || isLanAddress(addr) ||
                (addr.address.size == 4 && addr.address[0].toInt() and 0xff == 100 && (addr.address[1].toInt() and 0xc0) == 64) // CGNAT/Tailscale 100.64/10
        }

        /**
         * Private LAN address (RFC 1918, link-local, IPv6 ULA fc00::/7). Android 17+ only lets an app connect to
         * these with the ACCESS_LOCAL_NETWORK runtime permission.
         */
        fun isLanAddress(addr: InetAddress): Boolean =
            addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
                (addr.address.size == 16 && (addr.address[0].toInt() and 0xfe) == 0xfc)

        /**
         * True if [host] is (or resolves to) a LAN address or uses a local-only name (`.local`, `.lan`, …).
         * May do a DNS lookup: call it off the main thread.
         */
        fun resolvesToLan(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
            val addrs = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return false
            return addrs.any { isLanAddress(it) }
        }
    }
}

/**
 * NPM returns dates as ISO-8601 on MySQL/MariaDB (`2025-10-28T04:17:54.000Z`) and as
 * `2026-09-25 14:19:45` (UTC, no zone) on SQLite. Both parse to an [Instant].
 */
object NpmDates {
    private val sqlite = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        try { return Instant.parse(t) } catch (_: DateTimeParseException) {}
        try { return OffsetDateTime.parse(t).toInstant() } catch (_: DateTimeParseException) {}
        try { return LocalDateTime.parse(t, sqlite).toInstant(ZoneOffset.UTC) } catch (_: DateTimeParseException) {}
        try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC) } catch (_: DateTimeParseException) {}
        return null
    }
}

/** Input checks mirroring NPM's JSON schema, so mistakes show up in the form instead of as a 400. */
object Validation {
    // Same character blacklist as the schema's domain_names pattern.
    private val domainForbidden = Regex("[&| @!#%^();:/\\\\}{=+?<>,~`'\"]")
    private val ipv4 = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private val ipv4Cidr = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}(/([0-9]|[1-2][0-9]|3[0-2]))?$")
    private val hostnameChars = Regex("^[A-Za-z0-9._\\-\\[\\]:]+$")

    /** Null if valid, otherwise a short reason. Wildcards (`*.example.com`) are allowed like in NPM. */
    fun domainError(domain: String): String? {
        val d = domain.trim()
        return when {
            d.isEmpty() -> "Empty domain"
            domainForbidden.containsMatchIn(d) -> "\"$d\" contains characters that aren't allowed"
            d.length > 255 -> "\"$d\" is too long"
            d.startsWith('.') || d.endsWith('.') -> "\"$d\" can't start or end with a dot"
            else -> null
        }
    }

    /** Splits user input on commas, spaces and newlines; trims, lowercases and de-duplicates. */
    fun splitDomains(input: String): List<String> =
        input.split(',', ' ', '\n', '\t', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun portError(port: Int?): String? = when {
        port == null -> "Enter a port"
        port !in 1..65535 -> "Port must be 1–65535"
        else -> null
    }

    /** Forward host of a proxy host or location: a hostname or IP, optionally followed by a path. */
    fun forwardHostError(host: String, allowPath: Boolean = false): String? {
        val h = host.trim()
        if (h.isEmpty()) return "Enter a hostname or IP"
        if (h.contains("://")) return "Leave out the scheme (http://)"
        val hostPart = if (allowPath) h.substringBefore('/') else h
        if (!allowPath && h.contains('/')) return "Leave out the path"
        if (hostPart.isEmpty() || !hostnameChars.matches(hostPart)) return "Not a valid hostname or IP"
        if (hostPart.length > 255) return "Too long"
        return null
    }

    /** Redirection target: a domain name, optionally with a path (`example.com/new`). */
    fun redirectTargetError(target: String): String? {
        val t = target.trim()
        return when {
            t.isEmpty() -> "Enter a domain"
            t.contains("://") -> "Leave out the scheme; pick it below"
            t.endsWith('.') || t.startsWith('.') -> "Can't start or end with a dot"
            t.startsWith('*') -> "Wildcards aren't allowed here"
            else -> null
        }
    }

    /** Access list client: IPv4 (optionally CIDR), IPv6 (optionally /prefix) or `all`. */
    fun clientAddressError(address: String): String? {
        val a = address.trim()
        if (a == "all") return null
        if (ipv4Cidr.matches(a)) {
            val octets = a.substringBefore('/').split('.').map { it.toInt() }
            return if (octets.all { it in 0..255 }) null else "Not a valid IPv4 address"
        }
        if (a.contains(':')) {
            val addr = a.substringBefore('/')
            val prefix = a.substringAfter('/', "128").toIntOrNull()
            val ok = prefix != null && prefix in 0..128 && addr.all { it.isLetterOrDigit() || it == ':' || it == '.' } &&
                runCatching { InetAddress.getByName(addr) }.isSuccess
            return if (ok) null else "Not a valid IPv6 address"
        }
        return "Use an IP address, a CIDR range (10.0.0.0/8) or \"all\""
    }

    fun isIpv4(value: String) = ipv4.matches(value.trim())

    fun emailError(email: String): String? {
        val e = email.trim()
        return if (e.length >= 3 && e.contains('@') && !e.startsWith('@') && !e.endsWith('@') && !e.contains(' ')) null else "Enter a valid e-mail"
    }
}
