# Contributing to Focal

Focal is an independent MIT-licensed fork. Keep upstream attribution intact and
do not describe the project as maintained by Sonario's original author.

## Development baseline

1. Use JDK 17, Android SDK Platform 36, and Build Tools 35.0.0.
2. Use the tracked `./gradlew`; do not replace or bypass wrapper checksum checks.
3. Run `./gradlew test lint assembleDebug --stacktrace --no-daemon`.
4. For release-sensitive changes, also run `./gradlew assembleRelease`.

## Change requirements

- Map callers, persistence, UI, tests, and release effects before changing a
  provider, migration, downloader, or session schema.
- Keep provider IDs stable and credentials/models provider-scoped.
- Never add silent provider fallback.
- Add deterministic tests for parser, protocol, migration, queue, or downloader
  behavior at the lowest effective level.
- Explain user-visible changes and any migration or privacy impact in the PR.
- Do not commit generated APK/AAB files, GGUF files, `local.properties`, IDE
  state, `.serena/`, credentials, keystores, or passwords.

## Reports

Include reproducible steps, Focal version, device/API level, and non-sensitive
diagnostics. Remove API keys, cookies, private source text, provider response
bodies, and personal URLs. For cloud issues, name the selected provider/model;
for local inference, name the exact GGUF and device architecture.

See [SECURITY.md](SECURITY.md) for reporting a vulnerability privately.
