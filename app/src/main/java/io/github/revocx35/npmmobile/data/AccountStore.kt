package io.github.revocx35.npmmobile.data

import androidx.core.content.edit
import android.content.Context
import io.github.revocx35.npmmobile.core.ServerAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** A saved NPM server + user. Secrets are not in here; see [AccountStore.token] / [AccountStore.password]. */
@Serializable
data class Account(
    val id: String,
    val server: String,
    val email: String,
    val name: String = "",
    val npmVersion: String? = null,
    /** Expiry of the stored token (epoch millis), if NPM told us. */
    val tokenExpires: Long? = null,
    val tokenEnc: String? = null,
    /** Only set when the user chose "stay signed in": used to renew an expired session silently. */
    val passwordEnc: String? = null,
) {
    val address: ServerAddress get() = ServerAddress.parse(server) ?: error("Stored server address is invalid: $server")
    val label: String get() = name.ifBlank { email }
    val host: String get() = address.baseUrl.host + (address.baseUrl.port.takeIf { it != 80 && it != 443 }?.let { ":$it" } ?: "")
    val remembersPassword: Boolean get() = passwordEnc != null
}

/**
 * Saved accounts in private SharedPreferences (excluded from backups). Tokens and remembered passwords are
 * encrypted with [cipher] (Android Keystore). All methods are cheap and safe to call from the main thread.
 */
class AccountStore(context: Context, private val cipher: SecretCipher) {
    private val sp = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Account.serializer())

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow(sp.getString(KEY_ACTIVE, null)?.takeIf { id -> _accounts.value.any { it.id == id } })
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val active: Account? get() = _accounts.value.firstOrNull { it.id == _activeId.value }

    private fun load(): List<Account> =
        sp.getString(KEY_LIST, null)?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            ?.filter { ServerAddress.parse(it.server) != null }
            ?: emptyList()

    @Synchronized
    private fun persist(list: List<Account>) {
        sp.edit { putString(KEY_LIST, json.encodeToString(listSerializer, list)) }
        _accounts.value = list
    }

    /** Adds or updates the account for this server + e-mail and returns it. */
    @Synchronized
    fun upsert(
        server: ServerAddress, email: String, name: String, npmVersion: String?,
        token: String, tokenExpires: Long?, password: String?,
    ): Account {
        val existing = _accounts.value.firstOrNull { it.server == server.display && it.email.equals(email, ignoreCase = true) }
        val account = Account(
            id = existing?.id ?: UUID.randomUUID().toString(),
            server = server.display,
            email = email,
            name = name,
            npmVersion = npmVersion,
            tokenExpires = tokenExpires,
            tokenEnc = cipher.encrypt(token),
            passwordEnc = password?.let(cipher::encrypt),
        )
        persist(_accounts.value.filterNot { it.id == account.id } + account)
        return account
    }

    @Synchronized
    fun update(id: String, change: (Account) -> Account) {
        val list = _accounts.value.map { if (it.id == id) change(it) else it }
        persist(list)
    }

    fun setToken(id: String, token: String?, expires: Long?) =
        update(id) { it.copy(tokenEnc = token?.let(cipher::encrypt), tokenExpires = if (token == null) null else expires) }

    fun setPassword(id: String, password: String?) = update(id) { it.copy(passwordEnc = password?.let(cipher::encrypt)) }

    fun token(account: Account): String? = account.tokenEnc?.let(cipher::decrypt)

    fun password(account: Account): String? = account.passwordEnc?.let(cipher::decrypt)

    fun setActive(id: String?) {
        sp.edit { putString(KEY_ACTIVE, id) }
        _activeId.value = id
    }

    @Synchronized
    fun remove(id: String) {
        persist(_accounts.value.filterNot { it.id == id })
        if (_activeId.value == id) setActive(_accounts.value.firstOrNull()?.id)
    }

    private companion object {
        const val KEY_LIST = "accounts_v1"
        const val KEY_ACTIVE = "active_id"
    }
}
