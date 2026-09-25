package io.github.revocx35.npmmobile.data

import androidx.core.content.edit
import android.content.Context
import io.github.revocx35.npmmobile.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph, created once by [io.github.revocx35.npmmobile.NpmApp]. */
class AppGraph(context: Context, cipher: SecretCipher = KeystoreCipher()) {
    val diagnostics = Diagnostics(context)

    /** App-lifetime scope. A failure in one job is recorded instead of crashing the app. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> diagnostics.recordNonFatal("app scope", e) },
    )

    // NPM blocks "save" requests while it reloads nginx and, when a Let's Encrypt certificate is requested,
    // until certbot is done (DNS challenges wait for propagation), so reads may take minutes.
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val prefs = AppPrefs(context)
    val accounts = AccountStore(context, cipher)
    val session = SessionManager(accounts, http, scope, "NPM-Mobile/${BuildConfig.VERSION_NAME} (Android)")
    val repository = Repository(session, scope)
}

enum class ThemeMode { System, Light, Dark }

/** Non-secret UI preferences. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(sp.getString("theme", null)!!) }.getOrDefault(ThemeMode.System))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _dynamicColor = MutableStateFlow(sp.getBoolean("dynamic_color", true))
    /** Material You colors from the wallpaper (Android 12+). */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        sp.edit { putString("theme", mode.name) }
        _theme.value = mode
    }

    fun setDynamicColor(on: Boolean) {
        sp.edit { putBoolean("dynamic_color", on) }
        _dynamicColor.value = on
    }
}
