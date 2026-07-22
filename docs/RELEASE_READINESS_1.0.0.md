# Focal 1.0.0 release readiness

Assessment date: 2026-07-23 (Asia/Tokyo)

## Verdict

**The repository is an engineering-complete Focal 1.0.0 candidate, but it is
not ready for production publication.** Repository-level build, test, lint,
security-boundary, artifact, migration-function, CI-design, and documentation
gates pass. Production release remains blocked by unavailable arm64 device
validation, real provider credentials, and the owner-managed production signing
key. No final tag or GitHub Release has been created.

## Git evidence

| Item | Evidence |
| --- | --- |
| Tested branch | `integration/focal-1.0.0` |
| Tested source commit | `c5156b9f5ca9ccd29d928d5afe1d5e3455a8a318` |
| Remote integration branch | exact same commit, verified with `git ls-remote` |
| Baseline commit | `a24eb228881e53d221ec45b6157c40ca4d24f084` |
| Baseline tag | `baseline/focal-debug-repair-2026-07-23`; annotated tag dereferences to the baseline locally and remotely |
| Commits after baseline | 16 focused commits |
| Worktree during final matrix | clean except pre-existing untracked `.serena/` |
| Final release tag | absent locally and remotely |

The local `origin/integration/focal-1.0.0` tracking ref is stale because pushes
were sent directly over SSH without updating that local remote-tracking ref. Live
remote evidence, not the stale tracking line, confirms the branch target.

## Identity and artifact evidence

Both artifacts were produced from a clean build at the tested commit. Generated
outputs are ignored and are not tracked by Git.

| Artifact | Bytes | SHA-256 | Signature |
| --- | ---: | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 109,296,272 | `6432dbff9c36c4b43d3df0a4759760080ab6eedfe4aba7e3c369c8e338b95ca6` | Android debug certificate; APK Signature Scheme v2 verified |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 44,639,487 | `6a34e1a5c60934a0c4eeffe107328b82288f795ca6e75d4370e6e141894c0e4c` | intentionally unsigned; `apksigner` correctly reports `DOES NOT VERIFY` |

`aapt dump badging` confirms for both variants:

```text
package: ai.focal.app
versionCode: 11
versionName: 1.0.0
application label: Focal
minSdk: 28
targetSdk: 36
compileSdk: 36
native code: arm64-v8a
```

The APKs contain no non-arm64 native directory. Every merged dependency `.so`
reported by Gradle's strip task was independently identified as an already
stripped AArch64 ELF binary.

## Build, tests, and static analysis

Final authoritative command:

```bash
./gradlew clean test lint assembleDebug assembleRelease \
  -Pkotlin.compiler.execution.strategy=in-process \
  --console=plain --stacktrace --no-daemon
```

Result:

```text
BUILD SUCCESSFUL in 4m 41s
105 actionable tasks: 103 executed, 2 up-to-date
```

Test results:

| Variant | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Debug unit test | 39 | 0 | 0 | 0 |
| Release unit test | 39 | 0 | 0 | 0 |

Covered repository-level behavior includes provider IDs/configuration, OpenAI
and Anthropic serialization, Groq capability isolation, provider error mapping,
credential redaction/masking, credential/model/session migration functions,
rate-window and delay policies, catalog metadata/cleanup, downloader range and
integrity behavior, and summarization profiles.

Lint result: **0 errors, 38 warnings**. No lint baseline or blanket security
suppression is used.

| Count | Category | Classification |
| ---: | --- | --- |
| 17 | `UseKtx` | future maintenance; current private preference writes are explicit and tested |
| 13 | dependency newer-version notices | pinned compatibility set; upgrades require separate regression work |
| 1 | Gradle 8.14.5 available | Gradle 8.13 is the AGP 8.11.1-compatible pinned wrapper used here |
| 3 | `TrustAllX509TrustManager` | transitive Bouncy Castle 1.72 through PdfBox-Android 2.0.27.0; dependency limitation and continuing security review item |
| 2 | `UsableSpace` | conservative free-space check does not reclaim system cache; failure is safe/actionable |
| 1 | `MonochromeLauncherIcon` | optional Android themed-icon artwork; owner visual decision, not functional breakage |
| 1 | `ChromeOsAbiSupport` | intentional arm64-only product/dependency scope |

Local-only environment warnings also remain: the temporary SDK's older command
line tools report SDK XML schema 4 as newer than understood, and the sandbox
prevents Android analytics state under `~/.android`. Neither changes build output.

## Security and privacy findings

- Tracked-text scanning found no private keys or production-shaped API tokens.
- Git tracks no APK, AAB, APKS, keystore, or GGUF artifact.
- Credentials use per-provider AES-256-GCM with Android Keystore and fail closed;
  legacy plaintext/Base64 values are removed only after successful encryption.
- Credentials are absent from session JSON and excluded from backup/transfer.
- Only `sonario_settings.xml` is allowlisted for encrypted cloud backup/device
  transfer; models, sessions/private source text, crash data, and keys are excluded.
- Remote endpoints require HTTPS; cleartext is limited to exact same-device
  loopback hosts. URL user-info, query, and fragment values are rejected.
- Untrusted provider/crash messages are bounded and credential-redacted before
  UI, session, or private crash-file persistence.
- Catalog downloads require exact size and SHA-256 before atomic finalization.
- The app-owned exported component is `MainActivity`, required for launcher and
  `ACTION_SEND`. App-owned `FileProvider` and `SummaryService` are not exported.
  AndroidX's merged `ProfileInstallReceiver` is exported but protected by the
  platform `android.permission.DUMP` permission; its initialization provider is
  not exported.
- The release manifest contains only INTERNET, network-state, wake/Wi-Fi lock,
  foreground data-sync service, and notification permissions plus AndroidX's
  signature-level dynamic-receiver permission.
- MIT attribution remains exact: `Copyright (c) 2026 pgotta`; NOTICE states the
  independent fork relationship without implying official continuation.

The Bouncy Castle lint warning described above is not suppressed. Dependency
inspection proves it arrives through PdfBox-Android. Focal uses PdfBox for text
extraction and does not intentionally instantiate that permissive trust manager,
but removal or upgrade requires a separately tested PDF stack change.

## Persistence and migration status

Pure migration coverage passes for legacy Groq engine/model fields, missing
provider fields, unsupported provider/engine values, retired defaults, and
credential migration semantics. Unknown provider IDs remain visible and block
silent fallback. Sessions store provider/model but never a credential.

Not validated without a device/data fixture:

- Android in-place upgrade from a repaired `ai.focal.app` APK signed by the same key;
- debug-to-production signing transition (normally not an in-place upgrade);
- Keystore behavior and credential migration on representative hardware;
- Android backup/device-transfer restore behavior;
- process-death restoration with real multi-gigabyte model/session state.

Upstream Sonario packages with a different application ID are separate apps and
cannot upgrade in place to `ai.focal.app`.

## CI and signing status

Tracked workflow triggers are intentionally limited to:

- `pull_request` and pushes to `main`: wrapper validation, tests, lint, debug build;
- `workflow_dispatch`: clean debug APK plus diagnostic log artifacts;
- pushes of `v*` tags: require all four protected signing secrets, verify version
  equals the tag, run clean tests/lint, build and verify a signed release, calculate
  SHA-256, and upload an Actions artifact.

All workflows use read-only contents permission and stable-major action pins. The
tag workflow does not create a GitHub Release. These new workflows have valid
YAML, and their Gradle tag/signing/build paths were exercised locally, but they
have not yet run on GitHub-hosted infrastructure because this integration branch
has not been opened as a pull request, pushed to `main`, or tagged.

Signing configuration was tested with a disposable one-day RSA key: a signed
release built and verified with APK Signature Scheme v2. Partial signing variables
fail during Gradle configuration. The disposable key is not a production key and
was not committed. Permanent-key generation, backup, secrets, and certificate
acceptance remain owner actions.

## Device and emulator validation

`adb devices -l` reported no attached device. No emulator executable, configured
AVD, or `/dev/kvm` access is available in this environment. Therefore none of the
following is claimed:

- install, launch, onboarding, rotation, or process recreation;
- upgrade/uninstall/backup/restore behavior;
- YouTube/web/document end-to-end extraction;
- authenticated Groq, OpenAI, Anthropic, Ollama, or custom endpoint calls;
- GGUF download interruption/resume on Android storage;
- Qwen3, Gemma 3n, or LFM2 loading, memory, thermals, speed, or output quality;
- release APK installation across API 28–36.

## Tooling degradation

Serena's Kotlin language server previously failed initialization with
`cancelled (-32800)`. This was treated as non-blocking; no repair was attempted.
The pre-existing untracked `.serena/` directory remains untouched and uncommitted.

## Release blockers and minimum owner actions

1. Supply or create the permanent production signing key using the process in
   `docs/RELEASING.md`, back it up, and install all four protected GitHub secrets.
2. Provide an arm64 Android device (and, ideally, additional representative API
   levels) for the device matrix above, including all three advertised GGUFs.
3. Provide owner-controlled test credentials/endpoints for every advertised cloud
   provider path; confirm provider terms, billing, model IDs, and actual responses.
4. Open a pull request so the GitHub-hosted validation workflow runs and passes.
5. Review and accept or resolve the PdfBox/Bouncy Castle dependency warning and
   the arm64-only/monochrome-icon limitations.
6. After all blockers pass, approve the exact commit, create/push `v1.0.0`, inspect
   the signed artifact/certificate/checksum, then separately approve any GitHub
   Release or store publication.

Recommended response from the owner: provide the available arm64 test device/API
level and confirm readiness to perform permanent signing setup. Do not send keys
or passwords in this task.
