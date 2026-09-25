package io.github.revocx35.npmmobile.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.revocx35.npmmobile.core.NpmException
import io.github.revocx35.npmmobile.data.AppGraph
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

/** A failure as shown to the user: NPM's message plus optional technical details. */
data class UiError(val message: String, val details: String? = null)

fun Throwable.toUiError(): UiError = when (this) {
    is NpmException.Api -> UiError(message ?: "Request failed", details)
    is NpmException -> UiError(message ?: "Request failed")
    else -> UiError("Unexpected error (${javaClass.simpleName}${message?.let { ": ${it.take(160)}" } ?: ""})")
}

object Format {
    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    fun dateTime(i: Instant?): String = i?.let { dateTime.withLocale(Locale.getDefault()).format(it.atZone(ZoneId.systemDefault())) } ?: "—"
    fun date(i: Instant?): String = i?.let { date.withLocale(Locale.getDefault()).format(it.atZone(ZoneId.systemDefault())) } ?: "—"

    /** "just now", "5 min ago", "3 h ago", "2 days ago", or the date for older entries. */
    fun ago(i: Instant?, now: Instant = Instant.now()): String {
        if (i == null) return "—"
        val d = Duration.between(i, now)
        return when {
            d.isNegative || d.seconds < 60 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            d.toDays() < 7 -> plural(d.toDays(), "day") + " ago"
            else -> date(i)
        }
    }

    fun plural(n: Long, word: String) = "$n $word" + if (n == 1L) "" else "s"
    fun plural(n: Int, word: String) = plural(n.toLong(), word)
}

enum class ExpiryLevel { Ok, Soon, Critical, Expired, Unknown }

data class Expiry(val text: String, val level: ExpiryLevel, val days: Long?)

/** Human text and urgency for a certificate expiry date. */
fun expiryOf(expires: Instant?, now: Instant = Instant.now()): Expiry {
    if (expires == null) return Expiry("Unknown expiry", ExpiryLevel.Unknown, null)
    val days = Duration.between(now, expires).toDays()
    return when {
        expires.isBefore(now) -> Expiry("Expired ${Format.date(expires)}", ExpiryLevel.Expired, days)
        days < 1 -> Expiry("Expires today", ExpiryLevel.Critical, days)
        days < 7 -> Expiry("Expires in ${Format.plural(days, "day")}", ExpiryLevel.Critical, days)
        days < 21 -> Expiry("Expires in ${Format.plural(days, "day")}", ExpiryLevel.Soon, days)
        else -> Expiry("Expires ${Format.date(expires)}", ExpiryLevel.Ok, days)
    }
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open $url", Toast.LENGTH_SHORT).show()
    }
}

fun Context.copyToClipboard(label: String, text: String, sensitive: Boolean = false) {
    val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    cm.setPrimaryClip(clip)
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
}

/** The URL a host is served at, for "open in browser". Wildcard domains can't be opened. */
fun siteUrl(domain: String, https: Boolean): String? =
    if (domain.startsWith("*")) null else (if (https) "https://" else "http://") + domain
