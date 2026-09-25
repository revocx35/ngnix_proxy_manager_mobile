package io.github.revocx35.npmmobile.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Every failure of [NpmClient] is one of these, with a message that can be shown to the user as is. */
sealed class NpmException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No usable HTTP response (DNS, connection, TLS, timeout). */
    class Network(message: String, cause: Throwable?, val kind: Kind) : NpmException(message, cause) {
        enum class Kind { UnknownHost, Refused, Timeout, Tls, Other }
    }

    /** NPM answered with an error. [message] is NPM's own error text; [details] holds certbot/nginx output if any. */
    class Api(val status: Int, message: String, val details: String? = null) : NpmException(message)

    /** The session token is missing, expired or no longer accepted: the user has to sign in again. */
    class Unauthorized(message: String) : NpmException(message)

    /** Signed in, but the NPM user's permissions don't allow this action. */
    class Forbidden(message: String) : NpmException(message)

    /** The address answered, but not like an NPM API (HTML page, login portal, wrong port…). */
    class NotNpm(message: String, cause: Throwable? = null) : NpmException(message, cause)

    companion object {
        /** Maps an I/O failure to a readable [Network] error. */
        fun fromIo(e: IOException, host: String): Network = when (e) {
            is UnknownHostException -> Network("Can't find $host. Check the address and your connection.", e, Network.Kind.UnknownHost)
            is ConnectException -> Network("Can't connect to $host. Is the server running and the port right?", e, Network.Kind.Refused)
            is SocketTimeoutException -> Network("$host didn't respond in time.", e, Network.Kind.Timeout)
            is SSLPeerUnverifiedException, is SSLHandshakeException ->
                if (e.hasCause<CertificateException>()) {
                    Network("The HTTPS certificate of $host isn't trusted by Android (self-signed or incomplete chain).", e, Network.Kind.Tls)
                } else {
                    Network("Secure connection to $host failed: ${e.message ?: "TLS handshake error"}", e, Network.Kind.Tls)
                }
            is SSLException -> Network("Secure connection to $host failed: ${e.message ?: "TLS error"}", e, Network.Kind.Tls)
            else -> Network("Connection to $host failed: ${e.message ?: e.javaClass.simpleName}", e, Network.Kind.Other)
        }

        private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
            var c: Throwable? = this
            var depth = 0
            while (c != null && depth++ < 10) {
                if (c is T) return true
                c = c.cause
            }
            return false
        }

        /** Messages NPM uses for a token it no longer accepts (all sent with HTTP 400 by `AuthError`). */
        internal val AUTH_MESSAGES = listOf(
            "Token has expired",
            "Empty token",
            "Invalid token scope for User",
            "User cannot be loaded for Token",
            "User Token supplied without a User ID",
            "invalid signature",
            "jwt malformed",
        )
    }
}
