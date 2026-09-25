package io.github.revocx35.npmmobile

import android.util.Base64
import io.github.revocx35.npmmobile.data.AppGraph
import io.github.revocx35.npmmobile.data.SecretCipher

/** Robolectric has no Android Keystore: "encrypt" by base64 so the storage code paths still run. */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: String): String = "fake:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
    override fun decrypt(sealed: String): String? =
        if (sealed.startsWith("fake:")) String(Base64.decode(sealed.removePrefix("fake:"), Base64.NO_WRAP)) else null
}

class TestApp : NpmApp() {
    override fun createGraph() = AppGraph(this, FakeCipher())
}
