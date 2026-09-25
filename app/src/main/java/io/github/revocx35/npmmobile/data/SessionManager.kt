package io.github.revocx35.npmmobile.data

import io.github.revocx35.npmmobile.core.AuthToken
import io.github.revocx35.npmmobile.core.HealthStatus
import io.github.revocx35.npmmobile.core.NpmClient
import io.github.revocx35.npmmobile.core.NpmException
import io.github.revocx35.npmmobile.core.ServerAddress
import io.github.revocx35.npmmobile.core.User
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.Duration

sealed interface SessionState {
    /** Reading the stored session at startup. */
    data object Starting : SessionState

    /** No saved account: show the sign-in screen. */
    data object NoAccount : SessionState

    /** [user] is null until `/users/me` answered (e.g. while offline). */
    data class SignedIn(val account: Account, val client: NpmClient, val user: User?) : SessionState

    /** The saved account needs a new sign-in (token expired or rejected, or the user signed out). */
    data class NeedsSignIn(val account: Account, val reason: String?) : SessionState
}

/**
 * Owns the active account's [NpmClient] and keeps its token valid:
 * - renews the token (`GET /tokens`) when less than [renewBefore] of its lifetime is left,
 * - when NPM rejects the token, signs in again with the remembered password (if the user allowed that and the
 *   account has no 2FA), otherwise moves to [SessionState.NeedsSignIn].
 * All work that touches the Keystore or the network runs on [background].
 */
class SessionManager(
    private val store: AccountStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val userAgent: String,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val renewBefore: Duration = Duration.ofHours(12),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val authLock = Mutex()

    val accounts get() = store.accounts

    fun newClient(address: ServerAddress, token: String? = null) = NpmClient(address, http, token, userAgent)

    /** Loads the active account; call once at app start. */
    fun start() {
        scope.launch(background) { activate(store.active) }
    }

    private suspend fun activate(account: Account?) {
        if (account == null) {
            _state.value = SessionState.NoAccount
            return
        }
        val token = store.token(account)
        if (token == null) {
            if (!silentSignIn(account)) _state.value = SessionState.NeedsSignIn(account, null)
            return
        }
        val client = newClient(account.address, token)
        _state.value = SessionState.SignedIn(account, client, null)
        validate()
    }

    /**
     * Renews the token if needed and reloads the current user. Called at start and whenever the app returns to
     * the foreground. Network errors are ignored here (the screens show them); auth errors end the session.
     */
    fun validate() {
        lastValidated = clock()
        scope.launch(background) {
            val s = _state.value as? SessionState.SignedIn ?: return@launch
            try {
                renewIfNeeded(s)
                val me = call { it.me() }
                val current = _state.value as? SessionState.SignedIn ?: return@launch
                if (current.account.id == s.account.id) {
                    _state.value = current.copy(user = me)
                    if (me.displayName != current.account.name) store.update(current.account.id) { it.copy(name = me.displayName) }
                }
            } catch (_: NpmException.Network) {
                // Offline: keep the session, screens show the error and retry.
            } catch (e: NpmException.Api) {
                // A token signed with another key (NPM reinstalled) is a 500 "Internal Error" from /users/me.
                if (e.status == 500) endSession(s.account, "The server no longer accepts this session.")
            } catch (_: NpmException) {
                // Unauthorized was handled by call(); anything else is shown when screens load.
            }
        }
    }

    @Volatile private var lastValidated = 0L

    /** [validate], at most every few minutes (called whenever the app comes to the foreground). */
    fun validateIfStale() {
        if (clock() - lastValidated > Duration.ofMinutes(5).toMillis()) validate()
    }

    private suspend fun renewIfNeeded(s: SessionState.SignedIn) {
        val expires = s.account.tokenExpires ?: return
        val left = expires - clock()
        if (left > renewBefore.toMillis()) return
        if (left <= 0) {
            if (!reauthenticate(s.account, s.client)) endSession(s.account, "Your session expired.")
            return
        }
        authLock.withLock {
            val renewed = s.client.refreshToken()
            saveToken(s.account, s.client, renewed)
        }
    }

    /**
     * Runs an API call with the active client. If NPM rejects the token, tries one silent sign-in with the
     * remembered password and repeats the call; otherwise ends the session and rethrows.
     */
    suspend fun <T> call(block: suspend (NpmClient) -> T): T {
        val s = _state.value as? SessionState.SignedIn ?: throw NpmException.Unauthorized("Not signed in.")
        try {
            return block(s.client)
        } catch (e: NpmException.Unauthorized) {
            if (reauthenticate(s.account, s.client)) return block(s.client)
            endSession(s.account, "Your session expired. Please sign in again.")
            throw e
        }
    }

    /** Current client, for screens that need the server address. */
    val client: NpmClient? get() = (_state.value as? SessionState.SignedIn)?.client

    private suspend fun reauthenticate(account: Account, client: NpmClient): Boolean = authLock.withLock {
        val fresh = store.accounts.value.firstOrNull { it.id == account.id } ?: return false
        // Another caller may have renewed the token while we waited for the lock.
        val stored = withContext(background) { store.token(fresh) }
        if (stored != null && stored != client.token && (fresh.tokenExpires ?: Long.MAX_VALUE) > clock()) {
            client.token = stored
            return true
        }
        val password = withContext(background) { store.password(fresh) } ?: return false
        val result = runCatching { newClient(fresh.address).login(fresh.email, password) }.getOrNull()
        val token = (result as? NpmClient.LoginResult.Success)?.token ?: return false
        saveToken(fresh, client, token)
        true
    }

    private suspend fun silentSignIn(account: Account): Boolean {
        val client = newClient(account.address)
        if (!reauthenticate(account, client)) return false
        _state.value = SessionState.SignedIn(store.accounts.value.first { it.id == account.id }, client, null)
        validate()
        return true
    }

    private suspend fun saveToken(account: Account, client: NpmClient, token: AuthToken) {
        client.token = token.token
        withContext(background) { store.setToken(account.id, token.token, token.expiresAt?.toEpochMilli()) }
        val updated = store.accounts.value.firstOrNull { it.id == account.id } ?: return
        val current = _state.value
        if (current is SessionState.SignedIn && current.account.id == account.id) _state.value = current.copy(account = updated)
    }

    private fun endSession(account: Account, reason: String) {
        store.setToken(account.id, null, null)
        val current = _state.value
        val stillActive = (current as? SessionState.SignedIn)?.account?.id == account.id || (current as? SessionState.NeedsSignIn)?.account?.id == account.id
        if (stillActive) _state.value = SessionState.NeedsSignIn(store.accounts.value.firstOrNull { it.id == account.id } ?: account, reason)
    }

    /** Stores a successful sign-in (from the sign-in screen) and makes it the active account. */
    suspend fun completeSignIn(
        address: ServerAddress, email: String, token: AuthToken, rememberPassword: String?, health: HealthStatus?,
    ): Account = withContext(background) {
        val client = newClient(address, token.token)
        val me = client.me()
        val account = store.upsert(
            address, me.email.ifBlank { email }, me.displayName, health?.version?.toString(),
            token.token, token.expiresAt?.toEpochMilli(), rememberPassword,
        )
        store.setActive(account.id)
        _state.value = SessionState.SignedIn(account, client, me)
        account
    }

    fun switchTo(accountId: String) {
        scope.launch(background) {
            store.setActive(accountId)
            activate(store.active)
        }
    }

    /** Forgets the token (and remembered password) but keeps the account for a quick sign-in later. */
    fun signOut() {
        val account = when (val s = _state.value) {
            is SessionState.SignedIn -> s.account
            is SessionState.NeedsSignIn -> s.account
            else -> return
        }
        store.setPassword(account.id, null)
        store.setToken(account.id, null, null)
        _state.value = SessionState.NeedsSignIn(store.accounts.value.firstOrNull { it.id == account.id } ?: account, null)
    }

    fun removeAccount(accountId: String) {
        scope.launch(background) {
            val wasActive = store.activeId.value == accountId
            store.remove(accountId)
            if (wasActive) activate(store.active)
        }
    }

    /** Shows the sign-in screen for a new server without dropping the saved accounts. */
    fun addAnother() {
        _state.value = SessionState.NoAccount
    }

    /** Back from "add another server" to the account that was active. */
    fun cancelAddAnother() {
        scope.launch(background) { activate(store.active) }
    }

    fun setRememberPassword(password: String?) {
        val s = _state.value as? SessionState.SignedIn ?: return
        scope.launch(background) {
            store.setPassword(s.account.id, password)
            val updated = store.accounts.value.firstOrNull { it.id == s.account.id } ?: return@launch
            val current = _state.value
            if (current is SessionState.SignedIn && current.account.id == updated.id) _state.value = current.copy(account = updated)
        }
    }

    /** Updates the cached user after the profile was edited. */
    fun updateUser(user: User) {
        val current = _state.value as? SessionState.SignedIn ?: return
        if (current.user?.id == user.id || current.user == null) _state.value = current.copy(user = user.copy(permissions = user.permissions ?: current.user?.permissions))
    }
}
