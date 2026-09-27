# NPM Mobile: notes for Claude sessions

Native Android client (Kotlin + Jetpack Compose) for Nginx Proxy Manager. **Read `ARCHITECTURE.md` first.** It is
the design source of truth, including the NPM API quirks the code depends on (§3).

## Commands
- Everything CI runs: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  (JDK 21; Android SDK at `/opt/android-sdk` via `local.properties`, which isn't committed).
- Live tests against a disposable NPM: `scripts/dev-npm.sh up`, then export the printed `NPM_IT_URL`,
  `NPM_IT_EMAIL` and `NPM_IT_PASSWORD` and rerun the tests (`LiveNpmTest` and `LiveAppFlowTest` are skipped without them).
  `scripts/dev-npm.sh down` removes the container. `NPM_VERSION=2.16.0 scripts/dev-npm.sh up` tests another release.
- Screenshots of every screen with sample data: `./gradlew :app:recordRoborazziDebug --tests '*ScreenshotTest*'`
  writes `app/build/screenshots/*.png`; look at them after UI changes. README images are copies in `docs/screenshots/`.
  (Robolectric renders no system bars: check inset changes on the emulator.)
- Release build on a real Android (API 37 emulator, KVM): `scripts/emulator.sh start` (first time:
  `scripts/emulator.sh setup`), `scripts/dev-npm.sh up`, `./gradlew :app:assembleRelease`, then
  `python3 scripts/e2e-release.py`. It signs in (incl. the Android 17 permission prompt), visits every screen,
  creates/disables/deletes a host and restarts the app; screenshots in `build/e2e/`. Run it before every release.
- Icons: add to `ICONS` in `scripts/gen_icons.py`, run it, and use `AppIcons.X`. Don't add material-icons-extended.
- Test results summary: `core/build/test-results/test/*.xml`, `app/build/test-results/testDebugUnitTest/*.xml`.

## Rules
- **The owner's production NPM (`https://ngnix.mikoyan.men`) is off limits** except for unauthenticated read-only
  requests (`GET /api/`, `GET /api/schema`, i.e. `NPM_PROBE_URL`). Never sign in to it or change anything there
  unless the owner explicitly asks in that session. Do all authenticated testing on `scripts/dev-npm.sh`.
- Request bodies must only contain keys NPM's schema allows; `SchemaConformanceTest` enforces this. When targeting a
  newer NPM, download its `/api/schema` into `core/src/test/resources/` and re-run the tests before changing models.
- Response models: every field gets a default, booleans use `NBool` (NPM may send 0/1), and dates go through
  `NpmDates` (ISO or SQLite format). Never make a new field required.
- All network I/O goes through `NpmClient` (reads responses on `Dispatchers.IO`) and, in the app, through
  `SessionManager.call {}` (token renewal and re-auth). Nothing may throw on OkHttp threads.
- ViewModels: catch `Exception`, rethrow `CancellationException`, and show `toUiError()`. Don't swallow errors silently.
- Hide actions the NPM user may not perform (`User.canManage(Section)`): NPM 2.15.1 answers a denied write with a
  500, not a 403.
- Never log or display tokens, passwords, DNS provider credentials or private keys. Secrets are stored only through
  `AccountStore` (Keystore-encrypted).
- Release builds use R8 (shrink + optimize, `-dontobfuscate` so crash reports stay readable). Unit tests run
  unminified code, so a release is only verified by `scripts/e2e-release.py` on the emulator.
- Android 17 blocks connections to LAN addresses without the `ACCESS_LOCAL_NETWORK` runtime permission (a timeout,
  no error). `ui/LocalNetworkAccess.kt` asks for it at sign-in and in the shell. Robolectric runs SDK 35, so only the
  emulator exercises this.
- Commits end with the Co-Authored-By trailer. Keep CI green. Build from a fresh `git clone` before pushing (a local
  build can pass because of untracked files).

## Releasing
Bump `versionCode`/`versionName` in `app/build.gradle.kts`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
commit, then `git tag vX.Y.Z && git push --tags`. F-Droid picks the tag up automatically and ships our signed APK
only if its own build is byte-identical: keep the build reproducible (see `docs/FDROID.md`, and verify before
tagging when build config changes). The
`release` CI job signs with the `RELEASE_*` repository secrets and creates the GitHub release with the APK. The
signing keystore lives outside the repo; its location is in Claude's project memory. Never commit it or
`keystore.properties`. Losing it means users must uninstall to update.

## Environment gotchas (dev LXC)
- 16 GB RAM, 3 cores, ~8 GB free disk. Gradle is capped in `gradle.properties`; the emulator takes ~3 GB RAM.
- `/dev/kvm` is passed into the LXC (Proxmox Device Passthrough, mode 0666), so the emulator works. From inside the
  emulator the LXC's localhost is `10.0.2.2` (the test NPM is `http://10.0.2.2:18081`).
- `/tmp` (incl. Claude's scratchpad) is wiped when the container restarts.
- Unit tests use Robolectric `@Config(sdk = 35)` (via `app/src/test/resources/robolectric.properties`) with
  `TestApp`/`FakeCipher` in place of the Keystore; the emulator runs the real Keystore.
- Don't match your own command line with `pkill -f`/`pgrep -f` (e.g. `pkill -f emulator` kills the shell running it);
  use `scripts/emulator.sh stop` or match on `ps -eo comm`.
- `mipmap-anydpi-v26` must keep its `-v26` suffix: AAPT doesn't resolve the adaptive icon without it (lint.xml
  silences the warning).
- Roborazzi only writes images in record mode (`recordRoborazziDebug`); plain test runs don't.
