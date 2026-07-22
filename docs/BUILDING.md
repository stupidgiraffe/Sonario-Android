# Building Focal

## Required toolchain

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0
- Git and an internet connection for pinned Maven/Gradle dependencies

The repository pins Android Gradle Plugin 8.11.1, Kotlin/Compose plugin 2.2.20,
Gradle 8.13 with a distribution checksum, minimum API 28, and arm64 packaging.
Do not commit `local.properties`; configure the SDK through Android Studio or
`ANDROID_HOME`/`ANDROID_SDK_ROOT` locally.

## Commands

```bash
./gradlew test --stacktrace --no-daemon
./gradlew lint --stacktrace --no-daemon
./gradlew assembleDebug --stacktrace --no-daemon
./gradlew assembleRelease --stacktrace --no-daemon
```

For a fresh authoritative matrix:

```bash
./gradlew clean test lint assembleDebug assembleRelease --stacktrace --no-daemon
```

Outputs:

- debug: `app/build/outputs/apk/debug/app-debug.apk`
- unsigned release: `app/build/outputs/apk/release/app-release-unsigned.apk`
- signed release when configured: `app/build/outputs/apk/release/app-release.apk`

Use the SDK's `aapt dump badging` and `apksigner verify` to inspect identity and
signatures. An unsigned release failing `apksigner verify` is expected.

## Optional signing environment

Gradle signs only when all variables exist:

```text
FOCAL_RELEASE_KEYSTORE_PATH
FOCAL_RELEASE_KEY_ALIAS
FOCAL_RELEASE_STORE_PASSWORD
FOCAL_RELEASE_KEY_PASSWORD
```

Any partial set fails closed. Keep values in the shell/secret manager and keep
the keystore outside the repository. See [RELEASING.md](RELEASING.md).

## CI

- Pull requests and pushes to `main`: wrapper validation, tests, lint, debug APK.
- Manual workflow: clean debug APK and diagnostic log artifacts.
- `v*` tag: complete signing secrets, exact version/tag match, clean tests/lint,
  signed release APK, signature verification, SHA-256, and artifact upload.

The tag workflow does not publish a GitHub Release.
