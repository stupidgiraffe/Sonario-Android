# Architecture

## Runtime flow

`MainActivity` hosts the Compose screens and a process-aware
`SummaryViewModel`. The view model selects an `InferenceEngine`, coordinates
source extraction and `SummarizeEngine`, persists checkpoints through
`SessionStore`, and keeps long work visible through `SummaryService`.

Two inference implementations share the same contract:

- `LlmEngine` invokes Llamatik/llama.cpp against a verified private GGUF file.
- `CloudEngine` builds either an OpenAI-compatible request or a native Anthropic
  Messages request. It buffers a response before emitting chunks so a retry does
  not append a duplicated partial answer.

`SourceFetcher` handles pasted input, web pages, and YouTube transcript routes;
`FileTextExtractor` handles documents selected through Android's file picker.
`Exporter` writes TXT, Markdown, or PDF only to a URI selected by the user.

## Provider invariants

1. `LlmProvider.id` is the stable persistence identifier.
2. `Settings` stores model, endpoint, and temperature per provider.
3. `SecureStorage` stores credentials per provider outside ordinary settings.
4. `ProviderConfig` is captured before a request and validated before transport.
5. Anthropic uses its native wire format; other cloud options use the generic
   OpenAI-compatible format.
6. Groq-specific Qwen payload and quota behavior is guarded by provider/model
   capability checks.
7. Sessions store provider and model; unsupported values become visible migration
   errors and never trigger a silent provider substitution.

## Persistence boundaries

- `sonario_settings`: compatibility-named non-secret SharedPreferences.
- `sonario_secure_keys`: AES-GCM ciphertext per provider; the non-exportable key
  uses the stable Android Keystore alias `sonario_api_keys`.
- `filesDir/sonario_sessions/<id>`: schema-3 metadata, source text, chapters, and
  checkpoints. No credential is serialized.
- `filesDir/models`: completed and `.part` GGUF files.
- `filesDir/last_crash.txt`: one-shot, bounded, credential-redacted crash text.
- provider-scoped rate-limit preferences: request/token windows only, no secrets.

Historical `sonario_*` identifiers are preserved as compatibility IDs; they are
not the current product identity.

## Background and lifecycle

Long summaries use a non-exported data-sync foreground service, a bounded
six-hour CPU wake lock, and a Wi-Fi lock released with the service. Process-level
jobs survive activity recreation; durable checkpoints cover process death.
Cancellation propagates to OkHttp and local work where supported.

## Build/release boundary

The app is arm64-only because Llamatik ships prebuilt arm64 native libraries.
Release builds use R8 and resource shrinking. They remain unsigned unless every
`FOCAL_RELEASE_*` signing variable is present; partial configuration fails during
Gradle configuration.
