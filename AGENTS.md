# AGENTS.md: notes for AI agents

NPM Mobile is a native Android client (Kotlin, Jetpack Compose, Material 3) for Nginx Proxy Manager (NPM). It talks
straight to NPM's REST API; there is no backend. Design details and the NPM API quirks the code relies on are in
`ARCHITECTURE.md` (§3); session-specific rules are in `CLAUDE.md`.

## Layout
- `core/`: pure Kotlin/JVM. `NpmClient` (all HTTP), `Models.kt` (responses), `Requests.kt` (request bodies),
  `Json.kt` (`NpmJson`: snake_case, unknown keys ignored, nulls left out), validation, permissions.
- `app/`: Android app. `data/` (AppGraph DI, AccountStore with Keystore encryption, SessionManager, Repository),
  `ui/` (Compose screens; host editors in `ui/hosts/HostEditors.kt`), tests under `app/src/test` (Robolectric,
  `FakeNpmServer`, `Demo` data, screenshot tests).
- `core/src/test/resources/`: NPM's OpenAPI schemas (`npm-2.15.1-schema.json`, `npm-2.16.0-schema.json`) and
  recorded responses (`fixtures/`).
- `scripts/`: `dev-npm.sh` (disposable NPM in Docker), `emulator.sh`, `e2e-release.py`, `gen_icons.py`.
- `fastlane/metadata/`: store texts and per-versionCode changelogs (F-Droid reads them). `docs/FDROID.md`: reproducible builds.

## Commands
- CI: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (JDK 21, SDK at /opt/android-sdk).
- Live tests: `scripts/dev-npm.sh up` (NPM 2.16.0 on 127.0.0.1:18081; `NPM_VERSION=2.15.1` for the older release),
  export the printed `NPM_IT_URL/EMAIL/PASSWORD`, rerun the tests. `scripts/dev-npm.sh down` removes it.
- Screenshots: `./gradlew :app:recordRoborazziDebug --tests '*ScreenshotTest*'` → `app/build/screenshots/`.
- Before a release: `scripts/emulator.sh start`, `./gradlew :app:assembleRelease`, `python3 scripts/e2e-release.py`.
- Release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, add `changelogs/<versionCode>.txt`, commit,
  tag `vX.Y.Z`, push. CI signs the APK with repository secrets and creates the GitHub release.

## Conventions
- Every response field has a default; booleans use `NBool`; dates go through `NpmDates`.
- Request bodies may only contain keys NPM's schema allows (`SchemaConformanceTest` checks every supported release).
  Version-specific keys are nullable and left out when null.
- When the app rewrites a nested object (host locations), it must send back every field NPM returned, or NPM resets
  it. Example: NPM 2.16's `locations[].access_list_id`.
- ViewModels catch `Exception`, rethrow `CancellationException`, show `toUiError()`.
- Commits use the GitHub noreply identity and end with the Co-Authored-By trailer.

## Never do
- Never sign in to or change the owner's production NPM; only unauthenticated `GET /api/` and `GET /api/schema`.
- Never commit `keystore.properties`, `local.properties`, keystores or tokens; never log tokens, passwords, DNS
  credentials or private keys.
- Never make a model field required or add `material-icons-extended`.
- Never break reproducible builds (F-Droid ships our signed APK only if its build is byte-identical).

## Gotchas
- NPM 2.15.1 answers a denied write with 500; 2.16 with 403.
- The NPM 2.16 image won't start without `/data` and `/etc/letsencrypt` volumes.
- Robolectric runs SDK 35: Android 17's local network permission and R8 issues only show on the emulator.
- `pkill -f`/`pgrep -f` with a pattern from your own command line kills your shell; use `scripts/emulator.sh stop`.
- `mipmap-anydpi-v26` must keep its `-v26` suffix.
