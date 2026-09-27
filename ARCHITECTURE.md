# Architecture

NPM Mobile is a native Android client (Kotlin, Jetpack Compose, Material 3) for
[Nginx Proxy Manager](https://nginxproxymanager.com) (NPM). It talks directly to NPM's REST API: no WebView, no
backend of its own, no analytics.

- Target: NPM **2.15.x** (the API is checked against 2.15.1's OpenAPI schema; see §6).
- Android: minSdk 26, target/compileSdk 37. JDK 21 for Gradle, Kotlin compiled to JVM 17.
- Toolchain: Gradle 9.8, AGP 9.4 (compiles Kotlin itself), Kotlin 2.4, Compose BOM 2026.09 (Material 3 1.4),
  Navigation 2.10 (type-safe routes), OkHttp 5, kotlinx.serialization 1.11. Versions: `gradle/libs.versions.toml`.

## 1. Modules

```
core/   pure Kotlin/JVM, no Android: API models, request bodies, NpmClient (OkHttp), errors, validation
app/    Android app: session/storage (data/), Compose UI (ui/), tests with Robolectric
```

`core` has no Android dependency on purpose: the whole API layer is tested on the JVM, including against a real
NPM in Docker (`LiveNpmTest`). The app depends on `core` and adds storage, session handling and UI.

```
┌────────────── app ───────────────────────────────────────────────┐
│ ui/ (Compose screens + ViewModels)                               │
│    │ collect StateFlows             │ call suspend functions     │
│    ▼                                ▼                            │
│ data/Repository (cached lists) ── data/SessionManager ──┐        │
│                                     │ tokens            │        │
│                          data/AccountStore (prefs +     │        │
│                          Keystore-encrypted secrets)    │        │
└─────────────────────────────────────────────────────────┼────────┘
                                                          ▼
┌────────────── core ──────────────────────────────────────────────┐
│ NpmClient ── OkHttp ──► https://<npm>/api/...                    │
│ Models.kt (responses) · Requests.kt (bodies) · NpmException      │
│ Support.kt (ServerAddress, NpmDates, Validation) · Json.kt       │
└──────────────────────────────────────────────────────────────────┘
```

## 2. core

| File | Contents |
|---|---|
| `Json.kt` | `NpmJson` (snake_case naming strategy, ignore unknown keys, omit nulls), `NBool` lenient booleans (true/false/0/1), `CertificateRef` (`0` / id / `"new"`). |
| `Models.kt` | Response models: `ProxyHost`, `RedirectionHost`, `DeadHost` (404 host), `Stream` (all implement `NpmHost`), `AccessList`, `Certificate`, `User` + `UserPermissions`, `AuditLogEntry`, `Setting`, `HostReport`, `VersionCheck`, 2FA types. `HostKind` maps the four host types to API paths and permission `Section`s. |
| `Requests.kt` | Request bodies. They contain **only** keys NPM's schema allows (see §6). |
| `NpmClient.kt` | One client per server. Every endpoint as a `suspend` function. |
| `NpmException.kt` | The only error type callers see: `Network`, `Api(status, message, details)`, `Unauthorized`, `Forbidden`, `NotNpm`. |
| `Support.kt` | `ServerAddress` (normalizes user input to a base URL + `/api/`), `NpmDates` (ISO and SQLite formats), `Validation` (mirrors the schema's patterns so forms fail before the server does). |

### NpmClient rules
- **Threading:** requests are enqueued with OkHttp and awaited with `suspendCancellableCoroutine`; the body is read
  and parsed inside `withContext(io)`. Callers may call from the main thread. Cancelling the coroutine cancels the
  HTTP call immediately. OkHttp callbacks only resume the coroutine: nothing may throw on OkHttp threads.
- **Auth:** `token` is a `@Volatile var` read per request (`Authorization: Bearer …`). The session layer replaces it.
- **Action endpoints** (enable/disable/delete, permissions, password) ignore the response body and rely on the HTTP
  status, because NPM is inconsistent there (e.g. disabling a 404 host returns `{}` instead of `true`).
- **Errors:** NPM answers `{"error":{"code","message"},"debug":{"stack":[…]}}`. `message` becomes the exception
  message; `debug.stack` (certbot/nginx output) becomes `Api.details`, shown behind a "Details" button.
- **Timeouts:** the app's OkHttpClient has a 5-minute read timeout because saving a host with a new Let's Encrypt
  certificate, or a DNS challenge with propagation, blocks the request until certbot finishes.

## 3. NPM API facts the code relies on (verified on 2.15.1)

- `GET /api/` (no auth): `{"status":"OK","setup":true,"version":{…}}`. Used to validate an address before sign-in.
- `POST /tokens {identity, secret}` → `{token, expires}` **or** `{requires_2fa: true, challenge_token}`; then
  `POST /tokens/2fa {challenge_token, code}` (6-digit TOTP or 8-char backup code).
- Tokens live **1 day**. `GET /tokens` with a valid token returns a fresh one. There is no refresh token.
- Auth failures: expired token → **400** "Token has expired"; bad signature → **500** "Internal Error";
  no token on some routes → 404. `NpmClient` maps the known 400 messages and 401 to `Unauthorized`.
- **NPM bug:** a permission denial on writes is a **500** (lib/access.js throws `errs.PermissionError` without
  `new`). The app therefore hides actions the user may not perform, based on `/users/me?expand=permissions`.
- Dates are ISO on MySQL/MariaDB and `YYYY-MM-DD HH:MM:SS` (UTC) on SQLite.
- `GET /nginx/access-lists?expand=proxy_hosts` fails with 500; use `owner,items,clients` (`proxy_host_count` is
  included anyway). Access-list items with an empty password keep the stored password.
- Host `meta.nginx_online=false` + `meta.nginx_err` = nginx rejected the generated config ("Offline").
- `certificate_id: "new"` on a host save requests a Let's Encrypt certificate; the LE options go in the host's
  `meta` (`dns_challenge`, `dns_provider`, `dns_provider_credentials`, `propagation_seconds`, `key_type`).
  The LE account e-mail is the NPM user's e-mail (no `letsencrypt_email` field any more).
- Custom certificates: `POST /nginx/certificates {provider:"other", nice_name}`, then multipart
  `POST /nginx/certificates/{id}/upload` with fields `certificate`, `certificate_key`, `intermediate_certificate`.
  `/validate` takes the same multipart fields. Only Let's Encrypt certificates can be downloaded (zip).
- Location `forward_host` may contain a path (`10.0.0.5/app`); NPM splits it into host + `forward_path`.
- `/test-http` results per domain: `ok`, `no-host`, `failed`, `404`, `wrong-data`, `other:<detail>`.
- `/audit-log` returns the newest 100 entries (`?query=` filters server side).

## 4. app/data

| Class | Role |
|---|---|
| `AppGraph` | Manual DI, created once in `NpmApp.onCreate` (`createGraph()` is overridden by tests). Holds the app scope (`SupervisorJob` + handler that records non-fatal errors), one `OkHttpClient`, prefs, stores, session, repository. |
| `SecretCipher` / `KeystoreCipher` | AES-256-GCM with a non-exportable Android Keystore key. Tests use `FakeCipher` (Robolectric has no Keystore). |
| `AccountStore` | Saved accounts (server + e-mail + name + NPM version + token expiry) as JSON in private prefs; token and optional password stored **encrypted**. Backups are disabled for the whole app. |
| `SessionManager` | `StateFlow<SessionState>`: `Starting` → `NoAccount` / `SignedIn(account, client, user)` / `NeedsSignIn(account, reason)`. See below. |
| `Repository` | `Cached<T>` per collection (proxy hosts, certificates, …): `Resource(data, loading, error)`, de-duplicated loads, shared by all screens (lists, pickers, dashboard). Cleared when the active account changes. |
| `Diagnostics` | Local crash/ANR reports (More → Crash reports). Never uploaded, no server data. |

### Session lifecycle
1. `start()` loads the active account. No token → try a silent sign-in with the remembered password, else
   `NeedsSignIn`.
2. `validate()` (at start and on every resume, at most every 5 min): renew the token when < 12 h of its lifetime is
   left (`GET /tokens`), then reload `/users/me` (permissions can change).
3. `call { client -> … }` wraps every API call. On `Unauthorized` it tries **one** silent re-authentication
   (another caller's renewed token, or the remembered password) and repeats the call; otherwise it moves to
   `NeedsSignIn` ("Your session expired").
4. "Stay signed in" (remembered password) is opt-in, verified with a real sign-in, and not used for 2FA accounts
   (a TOTP code can't be replayed); enabling 2FA clears it.
5. Several servers can be saved; `switchTo(id)`, `addAnother()`, `removeAccount(id)`, `signOut()` (forgets token and
   password, keeps the account entry).

## 5. app/ui

- `AppRoot` picks the login screen or `MainShell` from `SessionState`, applies `NpmTheme` (brand scheme around
  NPM's orange, optional dynamic color, light/dark/system) and shows a one-time crash-report notice.
- `MainShell`: bottom navigation (Dashboard, Hosts, SSL, More) + `NavHost` with type-safe `@Serializable` routes
  (`Routes.kt`; `id = 0` means "new"). `LocalSession` exposes the signed-in account and user to screens.
- Screens own a `ViewModel` created with `viewModel { … }` from `LocalGraph`. Form state is Compose state inside the
  ViewModel; one-off results use nullable state fields (`message`, `finished`) consumed by `LaunchedEffect`.
- Host editors share `HostEditorViewModel` / `HostEditorScaffold` (load, validate → jump to the tab with the first
  error, save, enable/disable, delete, "discard changes?" via snapshots) and `SslForm` / `SslSection`
  (certificate picker incl. "request new", LE/DNS options, Force SSL/HTTP2/HSTS rules).
- Permissions: `User.canView/canManage(Section)` hide tabs, FABs, menus and Save buttons; admins see Users, Audit
  log and Default site.
- Icons are a generated subset of Material Symbols (`ui/theme/AppIcons.kt`, `scripts/gen_icons.py`) instead of the
  multi-MB icon library.

| Package | Screens |
|---|---|
| `login` | Sign in (address check, HTTP warning for public addresses, 2FA step, saved servers) |
| `dashboard` | Counters, NPM update notice, "needs attention" (offline hosts, certificates expiring < 21 days) |
| `hosts` | Tabs for proxy/redirection/stream/404 with search, status, quick enable/disable/delete; editors |
| `certificates` | List, detail (used by, renew, download zip, delete), new Let's Encrypt (reachability test, DNS challenge), custom upload with validation |
| `access` | Access lists and editor (basic-auth users, allow/deny rules, satisfy any, pass auth) |
| `users` | Users, editor with roles/permissions, set password |
| `audit` | Audit log with filter and event details (secrets redacted) |
| `settings` | More menu, profile (details, password, 2FA with QR code, stay signed in), servers, appearance, default site, crash reports, about |

## 6. Testing

| Test | What |
|---|---|
| `core` `ModelParsingTest` | Parses real NPM 2.15.1 responses (`core/src/test/resources/fixtures`, PEM data redacted). |
| `core` `SchemaConformanceTest` | Encodes every request class and checks each key/type/enum against NPM's own OpenAPI schema (`npm-2.15.1-schema.json`). Catches fields NPM would reject. |
| `core` `NpmClientTest` | MockWebServer: auth header, 2FA, error mapping, multipart, cancellation, and that responses are never read on the calling "UI" thread. |
| `core` `SupportTest` | Address normalization, local-network detection, validation, permissions. |
| `core` `LiveNpmTest` | Full round trip against a real NPM (`NPM_IT_*`): every object type, permissions, password, 2FA with a real TOTP. `probeHealthAndSchemaReadOnly` (`NPM_PROBE_URL`) only does unauthenticated GETs. |
| `app` `AppUiTest` | Robolectric + Compose UI against `FakeNpmServer`: sign-in, wrong password, 2FA, expired token, create/disable host, form validation. |
| `app` `LiveAppFlowTest` | The real UI against a real NPM (`NPM_IT_*`). |
| `app` `ScreenshotTest` | Renders the screens (light, dark, tablet) with `Demo` data to `app/build/screenshots` (Roborazzi record mode). |
| `scripts/e2e-release.py` | The signed, R8-minified release APK on an API 37 emulator (`scripts/emulator.sh`), driven through UI Automator: sign-in incl. the local network permission prompt, every screen, create/disable/delete a host (checked via the NPM API), app restart with the Keystore-stored session. Compose test tags are exposed as resource ids (`testTagsAsResourceId`). |

CI starts the NPM Docker image and runs the unit and live tests. The emulator e2e run is done locally before
each release (it needs KVM).

## 7. Security notes
- Tokens/passwords: Keystore-encrypted; app backups and device transfer are disabled.
- Cleartext HTTP is allowed (NPM's admin UI is typically `http://<lan-ip>:81`), but the sign-in screen requires
  explicit confirmation for HTTP to a non-local address. User-installed CAs are trusted so private-CA setups work.
- The app never logs tokens, passwords or DNS credentials; the audit-log viewer redacts `dns_provider_credentials`.
- Release builds: R8 shrinking/optimization without obfuscation (`app/proguard-rules.pro`); libraries ship their own
  keep rules. Verified per release with `scripts/e2e-release.py`.
- Android 17 local network protection: connections to LAN addresses (RFC 1918, link-local, ULA, `*.local`/`.lan`)
  need the `ACCESS_LOCAL_NETWORK` runtime permission, otherwise they silently time out. `ui/LocalNetworkAccess.kt`
  requests it before signing in to such a server (`ServerAddress.resolvesToLan`, DNS resolved off the main thread),
  offers it after a network failure, and prompts in `MainShell` if it's missing for the signed-in server.

## 8. Build, test, release
- `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (= CI).
- Live tests: `scripts/dev-npm.sh up`, export the printed `NPM_IT_*` vars, rerun the tests.
- Screenshots: `./gradlew :app:recordRoborazziDebug --tests '*ScreenshotTest*'` → `app/build/screenshots/`.
- Release check on the emulator: `scripts/emulator.sh start`, `./gradlew :app:assembleRelease`,
  `python3 scripts/e2e-release.py` → `build/e2e/`.
- Release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, commit, tag `vX.Y.Z`, push the tag. CI
  builds the signed APK with the `RELEASE_*` repository secrets and publishes a GitHub release. Local signed builds
  read `keystore.properties` (never committed).
- F-Droid: reproducible build from the tag, publishing our signed APK; store texts in `fastlane/`. Details and the
  local verification procedure: `docs/FDROID.md`.
