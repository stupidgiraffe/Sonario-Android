# Focal for Android

Focal summarizes pasted text, YouTube transcripts, web articles, and local
documents on Android. It can use a downloaded GGUF model on-device or a
bring-your-own-key cloud provider.

Focal is an independent fork of
[pgotta/Sonario-Android](https://github.com/pgotta/Sonario-Android). It is not
an official continuation maintained by Sonario's original author. The upstream
MIT copyright and license are preserved in [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Focal 1.0.0 release-candidate scope

- Application ID and namespace: `ai.focal.app`
- Minimum Android API: 28; target and compile API: 36
- Architectures packaged: `arm64-v8a`
- Cloud providers: Groq, OpenAI, native Anthropic, Ollama, and custom
  OpenAI-compatible endpoints
- Local models: Qwen3 4B Instruct 2507, Gemma 3n E4B Instruct, and LFM2 2.6B
- Durable provider-aware sessions and resumable summarization checkpoints
- TXT, Markdown, and PDF export through Android's Storage Access Framework

Local model compatibility and end-to-end cloud calls still require physical
device and owner-credential validation. The final evidence is recorded in the
[Focal 1.0.0 release-readiness report](docs/RELEASE_READINESS_1.0.0.md); the build
passing alone is not presented as device validation.

## Privacy and credentials

Cloud summarization sends the source and prompts to the provider selected by the
user. Focal does not silently switch providers. API keys are scoped per provider,
encrypted with AES-256-GCM using a key in Android Keystore, excluded from Android
backup/transfer, and never stored in saved sessions. Keystore hardware backing is
device-dependent and is not assumed.

Remote endpoints must use HTTPS. Cleartext HTTP is allowed only for exact device
loopback hosts (`localhost`, `127.0.0.1`, and `::1`) for a service running on the
same Android device.

See [docs/PROVIDERS.md](docs/PROVIDERS.md), [docs/BYOK.md](docs/BYOK.md), and
[SECURITY.md](SECURITY.md).

## Build and verify

Requirements: JDK 17, Android SDK Platform 36, and Build Tools 35.0.0. The
tracked wrapper downloads Gradle 8.13 and verifies its distribution checksum.

```bash
./gradlew test lint assembleDebug assembleRelease --stacktrace --no-daemon
```

The release APK is unsigned unless all four documented `FOCAL_RELEASE_*`
environment variables are provided. No keystore or password belongs in Git.
See [docs/BUILDING.md](docs/BUILDING.md) and
[docs/RELEASING.md](docs/RELEASING.md).

## Project documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Providers and endpoint policy](docs/PROVIDERS.md)
- [Local models and download integrity](docs/MODELS.md)
- [Persistence and migrations](docs/MIGRATIONS.md)
- [Verified repair baseline](docs/BASELINE.md)
- [Upstream parity decisions](docs/UPSTREAM_PARITY.md)
- [Changelog](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
