# F-Droid

The app is packaged for the official F-Droid repository with a **reproducible build**: F-Droid builds the tagged
source itself, checks that the result is identical to the signed APK attached to the GitHub release, and then
publishes *our* signed APK. Users can therefore switch between the GitHub and F-Droid versions without reinstalling.

- Recipe (lives in [fdroiddata](https://gitlab.com/fdroid/fdroiddata) as
  `metadata/io.github.revocx35.npmmobile.yml`; a copy of the submitted version is in
  [`fdroid-metadata.yml`](fdroid-metadata.yml)).
- Store texts, icon, screenshots and changelogs: `fastlane/metadata/android/en-US/` (F-Droid reads them from the
  tagged source). Add `changelogs/<versionCode>.txt` for every release.
- New versions are picked up automatically (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`): push a tag
  `vX.Y.Z` whose `versionName` is `X.Y.Z`. The CI release job must attach `npm-mobile-X.Y.Z.apk` (the `Binaries:`
  URL pattern) signed with the release key (`AllowedAPKSigningKeys`: certificate SHA-256
  `1699099a8e63dc332a2681f3eba20bcde15840bd46d18826ba1aedcb493d9c64`).

## Keeping the build reproducible
F-Droid builds on Debian trixie with its JDK 21 and `gradlew-fdroid` (Gradle from `gradle-wrapper.properties`,
verified against F-Droid's Gradle transparency log). CI builds with Temurin 21. v1.0.1 was verified identical.
Things that would break reproducibility, so avoid them or re-verify:
- the Google "dependency metadata" block (disabled via `dependenciesInfo` in `app/build.gradle.kts`),
- build timestamps, git hashes or machine paths in `BuildConfig`/resources,
- changing JDK major version, Gradle to a version F-Droid's transparency log doesn't have, or adding native code.

## Checking a release locally before tagging
```sh
python3 -m venv --without-pip /tmp/fd && curl -sS https://bootstrap.pypa.io/get-pip.py | /tmp/fd/bin/python3 - -q
/tmp/fd/bin/pip install -q fdroidserver
git clone --depth 1 --filter=blob:none --sparse https://gitlab.com/fdroid/fdroiddata.git /tmp/fdd
cd /tmp/fdd && git sparse-checkout set config templates
git clone --depth 1 https://gitlab.com/fdroid/gradlew-fdroid.git /tmp/gradlew-fdroid
printf 'sdk_path: /opt/android-sdk\ngradle: /tmp/gradlew-fdroid/gradlew.py\n' >> config.yml && chmod 600 config.yml
mkdir -p metadata && cp <repo>/docs/fdroid-metadata.yml metadata/io.github.revocx35.npmmobile.yml
# edit versionName/versionCode/commit for the new release, then (the recipe must be committed, or
# SOURCE_DATE_EPOCH ends up empty and Gradle fails with 'For input string: ""'):
git add --sparse metadata && git -c user.name=x -c user.email=x@x commit -qm test
/tmp/fd/bin/fdroid lint io.github.revocx35.npmmobile
/tmp/fd/bin/fdroid build -v -l --no-tarball io.github.revocx35.npmmobile   # builds from GitHub at `commit`
# compare with the CI-signed release APK:
/tmp/fd/bin/python3 -c "import tempfile; from fdroidserver import common; common.config=common.read_config(); \
print(common.verify_apks('npm-mobile-X.Y.Z.apk','unsigned/io.github.revocx35.npmmobile_<code>.apk',tempfile.mkdtemp()) or 'IDENTICAL')"
```
