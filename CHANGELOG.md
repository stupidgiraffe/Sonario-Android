# Changelog

All notable Focal changes are recorded here.

## 1.0.0 — release candidate

### Identity and lineage

- Establishes the independent Focal identity at `ai.focal.app`, version `1.0.0`
  (`versionCode` 11), while preserving upstream MIT attribution.
- Retains historical `sonario_*` preference, Keystore, notification-channel,
  and session-directory identifiers only where changing them would strand data.

### Providers and persistence

- Preserves Groq, OpenAI-compatible, native Anthropic, Ollama, and custom
  provider paths with stable IDs and provider-scoped credentials/models/endpoints.
- Saves the provider and model used by each session and refuses unsupported
  restored providers instead of silently falling back.
- Migrates legacy Groq engine/model fields and plaintext or historical Base64
  credentials only after Keystore encryption succeeds.

### Local models and downloads

- Updates Llamatik to 1.8.1 and exposes the verified Qwen3 4B, Gemma 3n E4B,
  and LFM2 2.6B catalog.
- Adds exact size and SHA-256 verification, safe range resume, free-space
  headroom, duplicate-request rejection, bounded writes, and atomic finalization.
- Cleans only allowlisted obsolete model files that no durable session references.

### Security, build, and release

- Restricts cleartext endpoints to exact device loopback hosts and requires HTTPS
  for remote custom endpoints.
- Redacts credential-shaped failure text before UI/session/crash persistence and
  keeps crash reports in private storage.
- Uses AGP 8.11.1, Gradle 8.13 with a pinned checksum, JDK 17, compile/target API
  36, R8 resource/code shrinking, release lint, and an executable wrapper.
- Adds PR/main validation, manual debug artifacts, and a signed tag workflow that
  fails closed without all owner-managed signing secrets.

### Verification limits

- JVM unit tests, lint, debug assembly, unsigned release assembly, manifest/APK
  inspection, and disposable-key signing verification are automated.
- Physical-device model loading, real provider calls, upgrade/backup restoration,
  and production-key signing remain explicit release gates.

## Pre-fork Sonario history

Earlier `1.1.x`–`1.5.x` Sonario history remains available in upstream Git. Focal
starts independent product versioning at 1.0.0; it does not claim those releases
as Focal releases.
