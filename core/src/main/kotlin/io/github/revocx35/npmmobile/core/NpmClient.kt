package io.github.revocx35.npmmobile.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Client for the Nginx Proxy Manager REST API (`/api`), one instance per server.
 *
 * Threading: every call switches to [io] and reads the whole response there, so callers can use it from
 * the main thread. Cancelling the calling coroutine cancels the HTTP call. Errors are always [NpmException].
 *
 * Authentication: NPM uses a bearer JWT (valid for 1 day by default). [token] is read for every request;
 * the owner of the client (the app's session layer) replaces it after [login] or [refreshToken].
 */
class NpmClient(
    val server: ServerAddress,
    private val http: OkHttpClient,
    @Volatile var token: String? = null,
    private val userAgent: String = "npm-mobile",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // ---- Server & authentication -------------------------------------------------------------------

    /** `GET /api/`: checks that the address is an NPM instance and returns its version. No token needed. */
    suspend fun health(): HealthStatus {
        val health = call(GET, "", auth = false, HealthStatus.serializer())
        if (health.status != "OK") throw NpmException.NotNpm("${server.display} answered, but not like Nginx Proxy Manager.")
        return health
    }

    sealed interface LoginResult {
        data class Success(val token: AuthToken) : LoginResult
        data class TwoFactorRequired(val challengeToken: String) : LoginResult
    }

    suspend fun login(email: String, password: String): LoginResult {
        val res = call(POST, "tokens", auth = false, TokenResponse.serializer(), LoginRequest.serializer(), LoginRequest(email.trim(), password))
        return when {
            res.requires2fa && res.challengeToken != null -> LoginResult.TwoFactorRequired(res.challengeToken)
            res.token != null -> LoginResult.Success(AuthToken(res.token, NpmDates.parse(res.expires)))
            else -> throw NpmException.NotNpm("The server's sign-in answer had no token.")
        }
    }

    /** Second step of a 2FA sign-in: a 6-digit TOTP code or an 8-character backup code. */
    suspend fun loginWith2fa(challengeToken: String, code: String): AuthToken {
        val res = call(
            POST, "tokens/2fa", auth = false, TokenResponse.serializer(),
            TwoFactorLoginRequest.serializer(), TwoFactorLoginRequest(challengeToken, code.filterNot { it.isWhitespace() }),
        )
        return AuthToken(res.token ?: throw NpmException.NotNpm("The server's 2FA answer had no token."), NpmDates.parse(res.expires))
    }

    /** `GET /tokens`: exchanges the current (still valid) token for a fresh one with a new expiry. */
    suspend fun refreshToken(): AuthToken {
        val res = call(GET, "tokens", auth = true, TokenResponse.serializer())
        return AuthToken(res.token ?: throw NpmException.Unauthorized("Session could not be renewed."), NpmDates.parse(res.expires))
    }

    suspend fun me(): User = call(GET, "users/me?expand=permissions", true, User.serializer())

    // ---- Dashboard -----------------------------------------------------------------------------------

    suspend fun hostReport(): HostReport = call(GET, "reports/hosts", true, HostReport.serializer())

    /** Asks the server whether a newer NPM release exists (the server queries GitHub). */
    suspend fun versionCheck(): VersionCheck = call(GET, "version/check", true, VersionCheck.serializer())

    // ---- Hosts ----------------------------------------------------------------------------------------

    suspend fun proxyHosts(): List<ProxyHost> =
        call(GET, "nginx/proxy-hosts?expand=owner,access_list,certificate", true, ListSerializer(ProxyHost.serializer()))

    suspend fun proxyHost(id: Int): ProxyHost =
        call(GET, "nginx/proxy-hosts/$id?expand=owner,access_list,certificate", true, ProxyHost.serializer())

    suspend fun createProxyHost(body: ProxyHostRequest): ProxyHost =
        call(POST, "nginx/proxy-hosts", true, ProxyHost.serializer(), ProxyHostRequest.serializer(), body)

    suspend fun updateProxyHost(id: Int, body: ProxyHostRequest): ProxyHost =
        call(PUT, "nginx/proxy-hosts/$id", true, ProxyHost.serializer(), ProxyHostRequest.serializer(), body)

    suspend fun redirectionHosts(): List<RedirectionHost> =
        call(GET, "nginx/redirection-hosts?expand=owner,certificate", true, ListSerializer(RedirectionHost.serializer()))

    suspend fun redirectionHost(id: Int): RedirectionHost =
        call(GET, "nginx/redirection-hosts/$id?expand=owner,certificate", true, RedirectionHost.serializer())

    suspend fun createRedirectionHost(body: RedirectionHostRequest): RedirectionHost =
        call(POST, "nginx/redirection-hosts", true, RedirectionHost.serializer(), RedirectionHostRequest.serializer(), body)

    suspend fun updateRedirectionHost(id: Int, body: RedirectionHostRequest): RedirectionHost =
        call(PUT, "nginx/redirection-hosts/$id", true, RedirectionHost.serializer(), RedirectionHostRequest.serializer(), body)

    suspend fun deadHosts(): List<DeadHost> =
        call(GET, "nginx/dead-hosts?expand=owner,certificate", true, ListSerializer(DeadHost.serializer()))

    suspend fun deadHost(id: Int): DeadHost =
        call(GET, "nginx/dead-hosts/$id?expand=owner,certificate", true, DeadHost.serializer())

    suspend fun createDeadHost(body: DeadHostRequest): DeadHost =
        call(POST, "nginx/dead-hosts", true, DeadHost.serializer(), DeadHostRequest.serializer(), body)

    suspend fun updateDeadHost(id: Int, body: DeadHostRequest): DeadHost =
        call(PUT, "nginx/dead-hosts/$id", true, DeadHost.serializer(), DeadHostRequest.serializer(), body)

    suspend fun streams(): List<Stream> =
        call(GET, "nginx/streams?expand=owner,certificate", true, ListSerializer(Stream.serializer()))

    suspend fun stream(id: Int): Stream =
        call(GET, "nginx/streams/$id?expand=owner,certificate", true, Stream.serializer())

    suspend fun createStream(body: StreamRequest): Stream =
        call(POST, "nginx/streams", true, Stream.serializer(), StreamRequest.serializer(), body)

    suspend fun updateStream(id: Int, body: StreamRequest): Stream =
        call(PUT, "nginx/streams/$id", true, Stream.serializer(), StreamRequest.serializer(), body)

    suspend fun enableHost(kind: HostKind, id: Int) {
        action(POST, "${kind.path}/$id/enable")
    }

    suspend fun disableHost(kind: HostKind, id: Int) {
        action(POST, "${kind.path}/$id/disable")
    }

    suspend fun deleteHost(kind: HostKind, id: Int) {
        action(DELETE, "${kind.path}/$id")
    }

    // ---- Access lists ----------------------------------------------------------------------------------

    // `expand=proxy_hosts` makes NPM 2.15.1 fail with a 500; `proxy_host_count` is included anyway.
    suspend fun accessLists(): List<AccessList> =
        call(GET, "nginx/access-lists?expand=owner,items,clients", true, ListSerializer(AccessList.serializer()))

    suspend fun accessList(id: Int): AccessList =
        call(GET, "nginx/access-lists/$id?expand=owner,items,clients", true, AccessList.serializer())

    suspend fun createAccessList(body: AccessListRequest): AccessList =
        call(POST, "nginx/access-lists", true, AccessList.serializer(), AccessListRequest.serializer(), body)

    suspend fun updateAccessList(id: Int, body: AccessListRequest): AccessList =
        call(PUT, "nginx/access-lists/$id", true, AccessList.serializer(), AccessListRequest.serializer(), body)

    suspend fun deleteAccessList(id: Int) {
        action(DELETE, "nginx/access-lists/$id")
    }

    // ---- Certificates ----------------------------------------------------------------------------------

    suspend fun certificates(): List<Certificate> =
        call(GET, "nginx/certificates?expand=owner", true, ListSerializer(Certificate.serializer()))

    suspend fun certificate(id: Int): Certificate =
        call(GET, "nginx/certificates/$id?expand=owner", true, Certificate.serializer())

    /**
     * Creates a certificate. For Let's Encrypt this blocks until certbot finished (can take minutes with a
     * DNS challenge), so the app uses a long read timeout. For a custom certificate call [uploadCertificate] next.
     */
    suspend fun createCertificate(body: CertificateRequest): Certificate =
        call(POST, "nginx/certificates", true, Certificate.serializer(), CertificateRequest.serializer(), body)

    suspend fun deleteCertificate(id: Int) {
        action(DELETE, "nginx/certificates/$id")
    }

    suspend fun renewCertificate(id: Int): Certificate =
        call(POST, "nginx/certificates/$id/renew", true, Certificate.serializer())

    /** ZIP with the certificate files. NPM only allows this for Let's Encrypt certificates. */
    suspend fun downloadCertificate(id: Int): ByteArray = execute(GET, "nginx/certificates/$id/download", true, null) { it }

    suspend fun uploadCertificate(id: Int, files: CertificateFiles) {
        execute(POST, "nginx/certificates/$id/upload", true, files.toMultipart()) { }
    }

    /** Checks certificate + key without storing anything. */
    suspend fun validateCertificate(files: CertificateFiles): CertificateValidation =
        execute(POST, "nginx/certificates/validate", true, files.toMultipart()) { decode(it, CertificateValidation.serializer()) }

    suspend fun dnsProviders(): List<DnsProvider> =
        call(GET, "nginx/certificates/dns-providers", true, ListSerializer(DnsProvider.serializer()))

    /** Asks NPM to check that each domain reaches this server over HTTP (needed for the HTTP-01 challenge). */
    suspend fun testHttpReachability(domains: List<String>): Map<String, String> = call(
        POST, "nginx/certificates/test-http", true, MapSerializer(String.serializer(), String.serializer()),
        TestHttpRequest.serializer(), TestHttpRequest(domains),
    )

    // ---- Users -----------------------------------------------------------------------------------------

    suspend fun users(): List<User> = call(GET, "users?expand=permissions", true, ListSerializer(User.serializer()))

    suspend fun user(id: Int): User = call(GET, "users/$id?expand=permissions", true, User.serializer())

    suspend fun createUser(body: UserRequest): User = call(POST, "users", true, User.serializer(), UserRequest.serializer(), body)

    suspend fun updateUser(id: Int, body: UserRequest): User =
        call(PUT, "users/$id", true, User.serializer(), UserRequest.serializer(), body.copy(auth = null))

    suspend fun deleteUser(id: Int) {
        action(DELETE, "users/$id")
    }

    suspend fun setPermissions(id: Int, permissions: UserPermissions) {
        action(PUT, "users/$id/permissions", UserPermissions.serializer(), permissions)
    }

    /** [current] is required when changing your own password, ignored by NPM when an admin sets someone else's. */
    suspend fun changePassword(userId: Int, newPassword: String, current: String?) {
        action(PUT, "users/$userId/auth", PasswordChangeRequest.serializer(), PasswordChangeRequest(newPassword, current))
    }

    suspend fun twoFactorStatus(userId: Int): TwoFactorStatus = call(GET, "users/$userId/2fa", true, TwoFactorStatus.serializer())

    /** Starts 2FA setup: returns the TOTP secret and the otpauth:// URL (show it as a QR code). */
    suspend fun startTwoFactorSetup(userId: Int): TwoFactorSetup = call(POST, "users/$userId/2fa", true, TwoFactorSetup.serializer())

    /** Confirms the setup with a code from the authenticator app and returns the backup codes. */
    suspend fun enableTwoFactor(userId: Int, code: String): BackupCodes =
        call(POST, "users/$userId/2fa/enable", true, BackupCodes.serializer(), TwoFactorCodeRequest.serializer(), TwoFactorCodeRequest(code.trim()))

    suspend fun disableTwoFactor(userId: Int, code: String) {
        action(DELETE, "users/$userId/2fa?code=${code.trim()}")
    }

    suspend fun regenerateBackupCodes(userId: Int, code: String): BackupCodes = call(
        POST, "users/$userId/2fa/backup-codes", true, BackupCodes.serializer(),
        TwoFactorCodeRequest.serializer(), TwoFactorCodeRequest(code.trim()),
    )

    // ---- Settings & audit log ------------------------------------------------------------------------

    suspend fun settings(): List<Setting> = call(GET, "settings", true, ListSerializer(Setting.serializer()))

    suspend fun updateSetting(id: String, body: SettingRequest): Setting =
        call(PUT, "settings/$id", true, Setting.serializer(), SettingRequest.serializer(), body)

    /** The newest 100 entries (NPM's limit), optionally filtered by NPM's server-side search. */
    suspend fun auditLog(query: String? = null): List<AuditLogEntry> {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }?.let { "&query=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return call(GET, "audit-log?expand=user$q", true, ListSerializer(AuditLogEntry.serializer()))
    }

    // ---- Plumbing --------------------------------------------------------------------------------------

    /**
     * For endpoints that answer with `true` on success. The body is ignored: some return `{}` instead
     * (e.g. disabling a 404 host in NPM 2.15.1), so the HTTP status is what counts.
     */
    private suspend fun action(method: String, path: String) {
        execute(method, path, true, null) { }
    }

    private suspend fun <B> action(method: String, path: String, requestSerializer: SerializationStrategy<B>, body: B) {
        execute(method, path, true, NpmJson.encodeToString(requestSerializer, body).toRequestBody(JSON)) { }
    }

    private suspend fun <T> call(method: String, path: String, auth: Boolean, response: DeserializationStrategy<T>): T =
        execute(method, path, auth, null) { decode(it, response) }

    private suspend fun <T, B> call(
        method: String, path: String, auth: Boolean, response: DeserializationStrategy<T>,
        requestSerializer: SerializationStrategy<B>, body: B,
    ): T {
        val json = NpmJson.encodeToString(requestSerializer, body).toRequestBody(JSON)
        return execute(method, path, auth, json) { decode(it, response) }
    }

    private fun <T> decode(bytes: ByteArray, strategy: DeserializationStrategy<T>): T = try {
        NpmJson.decodeFromString(strategy, bytes.decodeToString())
    } catch (e: SerializationException) {
        throw notNpm(bytes, e)
    } catch (e: IllegalArgumentException) {
        throw notNpm(bytes, e)
    }

    private fun notNpm(bytes: ByteArray, cause: Throwable): NpmException {
        val text = bytes.decodeToString().trimStart()
        val msg = if (text.startsWith("<")) {
            "${server.display} returned a web page instead of API data. Is this the NPM admin address (usually port 81)?"
        } else {
            "Unexpected response from ${server.display} (${cause.message?.substringBefore('\n')?.take(120)})."
        }
        return NpmException.NotNpm(msg, cause)
    }

    /** Sends the request, reads and maps the answer on [io], and hands a successful body to [read] (still on [io]). */
    private suspend fun <T> execute(method: String, path: String, auth: Boolean, body: RequestBody?, read: (ByteArray) -> T): T =
        withContext(io) {
            val builder = Request.Builder()
                .url(server.resolve(path))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .method(method, body ?: if (method == POST || method == PUT) EMPTY_JSON else null)
            if (auth) {
                val t = token ?: throw NpmException.Unauthorized("Not signed in.")
                builder.header("Authorization", "Bearer $t")
            }
            val response = try {
                http.newCall(builder.build()).await()
            } catch (e: IOException) {
                throw NpmException.fromIo(e, server.baseUrl.host)
            }
            response.use {
                val bytes = try {
                    it.body.bytes()
                } catch (e: IOException) {
                    throw NpmException.fromIo(e, server.baseUrl.host)
                }
                if (!it.isSuccessful) throw errorFor(it.code, bytes, auth)
                read(bytes)
            }
        }

    /**
     * Waits for the response headers without blocking a thread, and cancels the HTTP call as soon as the
     * coroutine is cancelled. The callbacks only resume the coroutine: nothing may throw on OkHttp's threads.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }

    private fun errorFor(status: Int, bytes: ByteArray, authenticated: Boolean): NpmException {
        val text = bytes.decodeToString()
        val parsed = runCatching { NpmJson.parseToJsonElement(text).jsonObject }.getOrNull()
        val error = parsed?.get("error")
        val message = when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        val details = ((parsed?.get("debug") as? JsonObject)?.get("stack") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { line -> line.isNotBlank() } }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
        if (parsed == null && text.trimStart().startsWith("<")) {
            return when (status) {
                401, 403 -> NpmException.NotNpm("${server.display} asked for another login (HTTP $status). Is the admin UI behind an extra auth proxy?")
                404 -> NpmException.NotNpm("${server.display} has no NPM API here (HTTP 404). Check the address and port.")
                502, 503, 504 -> NpmException.Api(status, "NPM isn't reachable behind ${server.display} right now (HTTP $status).")
                else -> NpmException.NotNpm("${server.display} returned a web page (HTTP $status) instead of API data.")
            }
        }
        val msg = message ?: "Request failed (HTTP $status)"
        return when {
            status == 401 -> NpmException.Unauthorized(msg)
            status == 403 -> NpmException.Forbidden(if (msg == "Permission Denied") "Your NPM user isn't allowed to do this." else msg)
            authenticated && status == 400 && NpmException.AUTH_MESSAGES.any { msg.contains(it, ignoreCase = true) } ->
                NpmException.Unauthorized(msg)
            else -> NpmException.Api(status, msg, details)
        }
    }

    private fun CertificateFiles.toMultipart(): RequestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
        addFormDataPart("certificate", "certificate.pem", certificate.toRequestBody(PEM))
        addFormDataPart("certificate_key", "certificate_key.pem", certificateKey.toRequestBody(PEM))
        intermediateCertificate?.let { addFormDataPart("intermediate_certificate", "intermediate_certificate.pem", it.toRequestBody(PEM)) }
    }.build()

    private companion object {
        const val GET = "GET"
        const val POST = "POST"
        const val PUT = "PUT"
        const val DELETE = "DELETE"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val PEM = "application/x-pem-file".toMediaType()
        val EMPTY_JSON = "{}".toRequestBody(JSON)
    }
}
