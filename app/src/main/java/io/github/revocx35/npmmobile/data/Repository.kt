package io.github.revocx35.npmmobile.data

import io.github.revocx35.npmmobile.core.AccessList
import io.github.revocx35.npmmobile.core.AuditLogEntry
import io.github.revocx35.npmmobile.core.Certificate
import io.github.revocx35.npmmobile.core.DeadHost
import io.github.revocx35.npmmobile.core.DnsProvider
import io.github.revocx35.npmmobile.core.HostKind
import io.github.revocx35.npmmobile.core.HostReport
import io.github.revocx35.npmmobile.core.NpmClient
import io.github.revocx35.npmmobile.core.NpmException
import io.github.revocx35.npmmobile.core.ProxyHost
import io.github.revocx35.npmmobile.core.RedirectionHost
import io.github.revocx35.npmmobile.core.Setting
import io.github.revocx35.npmmobile.core.Stream
import io.github.revocx35.npmmobile.core.User
import io.github.revocx35.npmmobile.core.VersionCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A cached server value: the last good [data], whether a load is running, and the last error. */
data class Resource<out T>(val data: T? = null, val loading: Boolean = false, val error: String? = null) {
    val isInitialLoading: Boolean get() = data == null && loading
}

/** One cached API collection. [refresh] is de-duplicated: concurrent callers share one request. */
class Cached<T>(private val scope: CoroutineScope, private val loader: suspend () -> T) {
    private val _state = MutableStateFlow(Resource<T>())
    val state: StateFlow<Resource<T>> = _state.asStateFlow()
    private val lock = Mutex()

    /** Loads once if nothing is cached yet. */
    fun ensure() {
        if (_state.value.data == null && !_state.value.loading) refresh()
    }

    fun refresh() {
        scope.launch { load() }
    }

    /** Suspends until the new data (or an error) is there. Errors are stored, not thrown. */
    suspend fun load() {
        if (lock.isLocked) {
            lock.withLock { } // someone else is loading: wait for them
            return
        }
        lock.withLock {
            _state.value = _state.value.copy(loading = true)
            _state.value = try {
                Resource(loader(), loading = false, error = null)
            } catch (e: CancellationException) {
                _state.value.copy(loading = false)
                throw e
            } catch (e: NpmException) {
                _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun set(value: T) {
        _state.value = Resource(value)
    }

    fun update(change: (T) -> T) {
        _state.value.data?.let { _state.value = _state.value.copy(data = change(it)) }
    }

    fun clear() {
        _state.value = Resource()
    }
}

/**
 * In-memory caches of the active server's data, shared by all screens (lists, pickers, dashboard).
 * Cleared when the active account changes. Loads go through [SessionManager.call] (token renewal).
 */
class Repository(private val session: SessionManager, private val scope: CoroutineScope) {
    private suspend fun <T> api(block: suspend (NpmClient) -> T): T = session.call(block)

    val proxyHosts = Cached(scope) { api { it.proxyHosts() } }
    val redirectionHosts = Cached(scope) { api { it.redirectionHosts() } }
    val streams = Cached(scope) { api { it.streams() } }
    val deadHosts = Cached(scope) { api { it.deadHosts() } }
    val accessLists = Cached(scope) { api { it.accessLists() } }
    val certificates = Cached(scope) { api { it.certificates() } }
    val users = Cached(scope) { api { it.users() } }
    val report = Cached(scope) { api { it.hostReport() } }
    val versionCheck = Cached<VersionCheck>(scope) { api { it.versionCheck() } }
    val settings = Cached<List<Setting>>(scope) { api { it.settings() } }
    val auditLog = Cached<List<AuditLogEntry>>(scope) { api { it.auditLog() } }
    val dnsProviders = Cached<List<DnsProvider>>(scope) { api { it.dnsProviders() } }

    private val all: List<Cached<*>> = listOf(
        proxyHosts, redirectionHosts, streams, deadHosts, accessLists, certificates, users, report,
        versionCheck, settings, auditLog, dnsProviders,
    )

    init {
        // Different server/user -> nothing cached may be shown any more.
        scope.launch {
            session.state.map { (it as? SessionState.SignedIn)?.account?.id }.distinctUntilChanged().collect { clearAll() }
        }
    }

    fun clearAll() = all.forEach { it.clear() }

    fun hosts(kind: HostKind): Cached<out List<*>> = when (kind) {
        HostKind.Proxy -> proxyHosts
        HostKind.Redirection -> redirectionHosts
        HostKind.Stream -> streams
        HostKind.Dead -> deadHosts
    }

    /** After a host was created/changed/deleted: its list and the dashboard counters are stale. */
    suspend fun hostsChanged(kind: HostKind) {
        hosts(kind).load()
        report.refresh()
    }

    // Simple typed accessors used by the UI.
    val proxyHostList: StateFlow<Resource<List<ProxyHost>>> get() = proxyHosts.state
    val redirectionList: StateFlow<Resource<List<RedirectionHost>>> get() = redirectionHosts.state
    val streamList: StateFlow<Resource<List<Stream>>> get() = streams.state
    val deadHostList: StateFlow<Resource<List<DeadHost>>> get() = deadHosts.state
    val accessListList: StateFlow<Resource<List<AccessList>>> get() = accessLists.state
    val certificateList: StateFlow<Resource<List<Certificate>>> get() = certificates.state
    val userList: StateFlow<Resource<List<User>>> get() = users.state
    val hostReport: StateFlow<Resource<HostReport>> get() = report.state
}
