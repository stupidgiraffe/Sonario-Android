# Focal Phase 0 Baseline

> Historical evidence: this file intentionally records the pre-integration
> baseline. Current toolchain, identity, tests, workflows, and release risks are
> documented in the other Focal 1.0.0 documents and release-readiness report.

Verified on 2026-07-23. This report records the repaired branch before any
upstream integration, package migration, or provider refactoring.

## Repository state

| Item | Commit | Notes |
| --- | --- | --- |
| Fork `main` | `c26af83cb8a6f8503b2469a614187b00f7b213a6` | Matches live `origin/main` |
| Repaired branch | `a24eb228881e53d221ec45b6157c40ca4d24f084` | `build/focal-debug-repair`; matches the live origin branch |
| Fork/repaired merge base | `c26af83cb8a6f8503b2469a614187b00f7b213a6` | Repaired branch is 46 commits ahead of fork `main` |
| Latest upstream `main` | `12d25e3c9c77b665f5e5b648c298d2364e70c255` | `Sonario 1.5.0: mobile-focused local LLM choices` |
| Upstream divergence commit | `c26af83cb8a6f8503b2469a614187b00f7b213a6` | Upstream has 2 unique commits; repaired branch has 46 unique commits |

The live fork and upstream branches were fetched into a temporary Git repository
for comparison, leaving this checkout's remotes and remote-tracking refs unchanged.

The verified repaired commit is marked by the annotated local tag:

```text
baseline/focal-debug-repair-2026-07-23
```

The tag uses the repository's GitHub noreply identity and resolves to
`a24eb228881e53d221ec45b6157c40ca4d24f084`.

## Applicable instructions

- The global Codex bootstrap and workspace-level `AGENTS.md` apply.
- No repository-local `AGENTS.md` or `AGENTS.override.md` exists in this checkout.
- Repository build guidance was inspected in `BUILD.md`, `CONTRIBUTING.md`,
  `README.md`, Gradle configuration, and the active GitHub Actions workflow.

## Build evidence

Required command:

```bash
./gradlew clean assembleDebug --stacktrace --no-daemon
```

Result:

```text
BUILD SUCCESSFUL in 1m 34s
38 actionable tasks: 38 executed
```

The successful run used JDK 17, Gradle 8.9, Android SDK Platform 36, and Android
SDK Build Tools 34.0.0. The machine had no default Android SDK location, so a
temporary SDK was used through process-local environment variables; no
`local.properties` file was created. The system Java 21 runtime lacked `jlink`,
so an existing complete JDK 17 was selected for the successful run.

The Gradle wrapper is tracked as mode `100644`. Its executable bit was enabled
temporarily so the required command could run, then restored to the tracked mode.
The manual GitHub workflow already performs the same `chmod +x gradlew` step.

Generated APK:

| Property | Value |
| --- | --- |
| Path | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | 111,538,246 bytes |
| SHA-256 | `43ca0779780612bb5439f178b3f91a7a6b3ecb71baf842767c5a5677c8e8e8d4` |
| Application ID | `ai.focal.app` |
| App label | `Focal` |
| Version | `1.4.0` (`versionCode` 10) |
| SDK range | minimum 28; target 36; compile 36 |

No device launch, upgrade migration, unit test, or instrumentation test was part
of this baseline build.

## Automation audit

- `.github/workflows/build.yml` is the only tracked GitHub Actions workflow.
- Its only trigger is `workflow_dispatch`; ordinary pushes, pull requests, tags,
  and schedules do not start an APK build.
- Workflow permissions are read-only for repository contents.
- The former duplicate `.github/workflows/android.yml` was deleted in `fda213c`.
- The former `codemagic.yaml` was deleted in `c876761`; no Codemagic, Travis CI,
  CircleCI, Jenkins, or Bitbucket pipeline configuration remains tracked.
- `.github/dependabot.yml` still opens monthly Gradle and GitHub Actions update
  pull requests, with at most five open PRs per ecosystem. This is automatic
  dependency maintenance, but it cannot trigger the manual-only build workflow.

No unexpected automatic build or release trigger is present at this baseline.

## Known risks and follow-up

- Android Gradle Plugin 8.7.3 is tested through compileSdk 35, while this project
  compiles and targets SDK 36. The build succeeds but emits a compatibility warning.
- `kotlinOptions.jvmTarget` uses a deprecated Gradle DSL.
- `LocalClipboardManager` is deprecated in two UI files, and a deprecated generic
  Groq model default is still referenced by `SummaryViewModel`.
- Several bundled native libraries could not be stripped and were packaged as-is.
- The tracked non-executable `gradlew` mode prevents the required command from
  running directly until execution permission is granted.
- A durable developer SDK location is not configured on this workstation.
- Serena's Kotlin language server failed initialization with `cancelled (-32800)`.
  Serena is optional for Phase 0; no repair was attempted.
- `.serena/` remains untracked and untouched. Its contents and tracking policy
  require a separate review.
- Remote tag presence and repository-level tag protection must be verified as
  part of baseline publication and repository policy management.
- The active namespace and several internal names remain `ai.sonario.app`, and
  the version is still 1.4.0 rather than Focal 1.0.0. Those are later migration work.

## Phase 0 conclusion

The repaired commit produces a clean debug APK and is preserved by an annotated
baseline tag. The fork and upstream reference points are known, and build CI is
manual-only. The recommended next phase is Phase 1: produce the upstream 1.5.0
parity matrix and selectively port local-model, download-hardening, rate-limit,
manifest, UI, and documentation improvements without replacing Focal's
multi-provider architecture.
