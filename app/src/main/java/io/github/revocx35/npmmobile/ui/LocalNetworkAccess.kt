package io.github.revocx35.npmmobile.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.revocx35.npmmobile.core.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 17 (API 37) blocks connections to private LAN addresses (192.168.x.x, 10.x, fd00::/8, *.local…) unless
 * the app holds the ACCESS_LOCAL_NETWORK runtime permission. Without it a connection just times out, which is
 * how a self-hosted NPM admin UI at http://192.168.1.10:81 looks unreachable.
 */
object LocalNetworkAccess {
    // Literal instead of Manifest.permission.ACCESS_LOCAL_NETWORK, which only exists in the API 37 SDK.
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    val enforced: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun granted(context: Context): Boolean =
        !enforced || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Whether connecting to [address] needs the permission first (DNS lookup off the main thread). */
    suspend fun needed(context: Context, address: ServerAddress): Boolean {
        if (granted(context)) return false
        return withContext(Dispatchers.IO) { ServerAddress.resolvesToLan(address.baseUrl.host) }
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    const val DENIED_MESSAGE =
        "Android blocks access to devices on your local network unless you allow it. Allow \"Local network access\" " +
            "(Nearby devices) for NPM Mobile to reach this server."
}

/**
 * Asks for local network access when the signed-in server is on the LAN but the permission is missing, e.g. it
 * was revoked or the phone was upgraded to Android 17 after signing in. [onGranted] reloads the data.
 */
@Composable
fun LocalNetworkPermissionPrompt(address: ServerAddress, onGranted: () -> Unit) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            show = false
            onGranted()
        } else {
            denied = true
        }
    }
    LaunchedEffect(address) { show = LocalNetworkAccess.needed(context, address) }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Allow local network access") },
        text = {
            Text(
                if (denied) LocalNetworkAccess.DENIED_MESSAGE
                else "${address.baseUrl.host} is on your local network. Android 17 requires your permission before NPM Mobile can connect to it.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (denied) LocalNetworkAccess.openAppSettings(context) else launcher.launch(LocalNetworkAccess.PERMISSION)
            }) { Text(if (denied) "Open settings" else "Allow") }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Not now") } },
    )
}
