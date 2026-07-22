# Upstream 1.5.0 Parity Record

## Scope and evidence

This record compares three immutable points:

| Role | Commit |
| --- | --- |
| Divergence / fork `main` | `c26af83cb8a6f8503b2469a614187b00f7b213a6` |
| Verified repaired Focal baseline | `a24eb228881e53d221ec45b6157c40ca4d24f084` |
| Verified upstream `main` | `12d25e3c9c77b665f5e5b648c298d2364e70c255` |

Upstream has two commits after the divergence point:

- `a482638` — Sonario 1.4.0: Qwen 3.6 with rate-aware cloud queueing.
- `12d25e3` — Sonario 1.5.0: mobile-focused local LLM choices.

The upstream range changes 14 files with 1,019 insertions and 545 deletions.
Focal has 46 repaired-only commits and a multi-provider architecture that is not
present upstream. Repository code, Git patches, Gradle declarations, manifests,
resources, and live public model metadata were inspected. There are currently no
unit-test or instrumentation-test source files.

Disposition terms used below:

- **Adopt directly** — behavior can be copied without changing Focal invariants.
- **Adapt for Focal** — behavior is useful but must fit Focal's architecture.
- **Already superseded by Focal** — Focal already provides a broader solution.
- **Reject intentionally** — behavior conflicts with Focal identity or invariants.
- **Requires owner decision** — repository evidence cannot select product behavior.

## File-by-file parity

| File | Upstream behavior | Focal behavior / conflict | Disposition |
| --- | --- | --- | --- |
| `CHANGELOG.md` | Documents Sonario 1.4.0 and 1.5.0. | Focal needs its own release history and honest fork lineage. | Adapt for Focal |
| `README.md` | Describes Groq/Qwen and the new local models as Sonario. | Focal supports multiple cloud providers and independent branding. | Adapt for Focal |
| `app/build.gradle.kts` | Moves Llamatik 1.7.0 to 1.8.1 and Sonario to version 1.5.0. | Focal needs Llamatik 1.8.1, but must keep BYOK dependencies, tests, and `ai.focal.app`; the final line starts at 1.0.0. | Adapt for Focal |
| `app/src/main/AndroidManifest.xml` | Adds legacy and Android 12+ backup rules and disables fragile-data retention. | Focal already uses `ai.focal.app` and `FocalApp`, but lacks the rule references. | Adapt for Focal |
| `Settings.kt` | Pins Groq to Qwen 3.6 and rewrites stale saved Groq model IDs; stores the Groq key in ordinary preferences. | Focal stores per-provider models and encrypted keys. It must migrate retired Groq defaults without collapsing provider choice or weakening storage. | Adapt for Focal |
| `GroqEngine.kt` | Adds Qwen-specific payload fields, live Groq quota headers, daily-limit detection, 5xx retries, and queue feedback. | `CloudEngine` supersedes the Groq-only engine. Groq-only policy must remain isolated while generic retry behavior remains provider-neutral. | Already superseded by Focal; adapt selected policies |
| `CloudEngine.kt` | Not present upstream. | Focal's OpenAI-compatible and native Anthropic engine is the required architecture. | Already superseded by Focal |
| `LlmEngine.kt` | Adds new catalog, Llamatik-tuned parameters, and one-time cleanup of three obsolete models and partials. | Focal still has the legacy catalog and Llamatik 1.7.0. Cleanup must be narrowly allowlisted and tested. | Adapt for Focal |
| `ModelDownloader.kt` | Adds free-space checks, redirect settings, Range-ignore recovery, larger buffers, resource-safe writes, and a plausibility check. | Focal has basic resume but appends a full `200` response to an existing partial and lacks storage validation. | Adapt for Focal |
| `RateLimiter.kt` | Implements Groq Qwen TPM/RPM/TPD windows, server header synchronization, and backoff state. | Focal's limiter is configurable but one shared Groq-shaped instance is used for every provider and lacks RPM/server-window state. | Adapt for Focal |
| `SummarizeEngine.kt` | Shrinks cloud chunks and budgets for Groq Qwen's free-tier limit. | Focal uses one `bigContext` switch for all cloud providers. Provider/model limits must drive budgets instead. | Adapt for Focal |
| `ModelsScreen.kt` | Adds use-case descriptions, formatted sizes, and private-storage explanation. | Behavior is useful; Sonario branding and subjective styling changes are not. | Adapt for Focal |
| `SettingsScreen.kt` | Removes model choice and presents Groq/Qwen-specific limits. | Focal must preserve provider and per-provider model selection. Current provider UI is incomplete and still uses Groq-centric state names. | Reject Groq-only UI; adapt factual quota/status copy |
| `SetupScreen.kt` | Presents the new catalog, formatted sizes, and Groq alternative. | Focal must present Focal branding and a provider-neutral cloud option. | Adapt for Focal |
| `data_extraction_rules.xml` | Excludes `files/models/` from cloud backup and device transfer. | The model exclusion is correct, but Focal must also assess credentials, partials, caches, sessions, and provider state. | Adapt for Focal |
| `backup_rules.xml` | Upstream manifest begins using a model exclusion equivalent to Focal's existing file. | Focal already excludes `files/models/` for legacy backup, but the manifest does not reference the file and credential preferences remain in scope. | Already present; adapt and wire it |

## Logical change records

### 1. Llamatik 1.8.1

- **Upstream behavior:** replaces Llamatik 1.7.0 with 1.8.1 for Qwen3, Gemma 3n,
  and LFM2 GGUF support; enables flash attention and uses lower-temperature
  generation parameters.
- **Focal behavior:** uses 1.7.0, a 4K context, memory mapping, and best-effort GPU
  offload. Local inference is behind `InferenceEngine` and selected by the view model.
- **Conflict/overlap:** dependency adoption is small, but runtime compatibility and
  parameter support must be proven by build and device tests.
- **Disposition:** **Adapt for Focal**.
- **Affected files:** `app/build.gradle.kts`, `LlmEngine.kt`, build documentation.
- **Migration risk:** an existing downloaded model may load differently after the
  native runtime changes; no persistent schema changes are required.
- **Tests:** debug/release builds, load/cancel/switch smoke tests, each new GGUF on a
  supported arm64 device, and regression loading of any retained model.
- **Dependency implication:** Kotlin 2.2.20 and the Compose toolchain must remain
  compatible with the 1.8.1 artifact.
- **User impact:** access to the new catalog; possible inference behavior changes.
- **Privacy/security:** remains in-process; native dependency provenance and bundled
  ABI contents must be reviewed.

### 2. Mobile model catalog

Live public metadata was verified on 2026-07-23 without downloading the payloads:

| Model | Upstream file | Verified bytes | Upstream display size | Public source status |
| --- | --- | ---: | ---: | --- |
| Qwen3 4B Instruct 2507 | `Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf` | 2,497,280,736 | 2,500 MB | HTTP 200 |
| Gemma 3n E4B Instruct | `gemma-3n-E4B-it-Q4_K_M.gguf` | 4,237,063,776 | 4,240 MB | HTTP 200 |
| LFM2 2.6B | `LFM2-2.6B-Q4_K_M.gguf` | 1,563,668,704 | 1,560 MB | HTTP 200 |

- **Upstream behavior:** replaces Qwen2.5 1.5B, Llama 3.2 3B, and Phi-3.5 Mini;
  shows a strength/tradeoff description and formatted download size.
- **Focal behavior:** still lists the three obsolete models and uses their local
  filenames as saved session/model identity.
- **Conflict/overlap:** catalog replacement affects existing model files, selected
  model state, and resumable sessions. A missing old model already fails safely,
  but deletion makes old on-device sessions non-resumable until a new model is chosen.
- **Disposition:** **Adapt for Focal**.
- **Affected files:** `LlmEngine.kt`, model/setup UI, `SummaryViewModel.kt`, migration
  tests, model documentation.
- **Migration risk:** destructive deletion of multi-gigabyte files and loss of the
  exact model referenced by an interrupted session.
- **Tests:** metadata accuracy, file-name mapping, catalog ordering, selected-model
  fallback, legacy session restoration, legacy cleanup allowlist, and device loading.
- **Dependency implication:** requires Llamatik 1.8.1.
- **User impact:** larger downloads for Qwen/Gemma, different quality/speed choices.
- **Privacy/security:** public HTTPS downloads remain in private app storage; model
  integrity is not currently verified cryptographically.

### 3. Downloader reliability

- **Upstream behavior:** checks free space with 256 MB headroom, follows redirects,
  restarts a partial when a Range request receives `200`, uses `use` for file closure,
  and rejects files below 85% of the catalog estimate.
- **Focal behavior:** resumes from `.part`, but seeks to the existing offset even
  when a host ignores Range; it has no free-space check and does not validate
  `Content-Range` or the expected final size.
- **Conflict/overlap:** upstream fixes the confirmed append-corruption case, but its
  85% heuristic is insufficient as an integrity guarantee and `renameTo` is not a
  complete atomic-finalization contract.
- **Disposition:** **Adapt for Focal**.
- **Affected files:** `ModelDownloader.kt`, `SummaryViewModel.kt`, model UI, tests.
- **Migration risk:** partial files may be stale or corrupt; cleanup must not delete
  a valid active download.
- **Tests:** `206` with valid range, `200` after Range, malformed/missing
  `Content-Range`, short body, unknown length, interruption, cancellation, restart,
  insufficient space, duplicate request, finalization failure, and retry.
- **Dependency implication:** existing OkHttp 4.12.0 is sufficient.
- **User impact:** fewer restarts/corrupt models and actionable storage errors.
- **Privacy/security:** validate destination names, response sizes, and HTTPS URLs;
  never follow a redirect into an unsupported cleartext endpoint silently.

### 4. Legacy model cleanup

- **Upstream behavior:** once per process, deletes the three old GGUF names and
  matching `.part` files when the models directory is accessed.
- **Focal behavior:** does not clean obsolete files; saved sessions retain a model
  filename and may depend on an old file.
- **Disposition:** **Adapt for Focal**, after migration tests.
- **Affected files:** `LlmEngine.kt`, `SessionStore.kt`, `SummaryViewModel.kt`, tests.
- **Migration risk:** irreversible local deletion and loss of session resumability.
- **Test requirement:** exact allowlist only, idempotence, active-download exclusion,
  session fallback, unrelated-file preservation, and failed-delete behavior.
- **User impact:** storage recovery versus loss of an intentionally retained model.
- **Privacy/security:** deletion is local and narrow; never scan or delete outside
  the private models directory.

### 5. Backup and device transfer

- **Upstream behavior:** wires both legacy and Android 12+ rules, excludes models
  from cloud backup/transfer, and sets `hasFragileUserData=false`.
- **Focal behavior:** has an unwired legacy `backup_rules.xml`; has no
  `data_extraction_rules.xml`. Encrypted-key ciphertext and the non-exportable
  Android Keystore key can be separated by backup, producing undecryptable state.
- **Disposition:** **Adapt for Focal**.
- **Affected files:** manifest, both backup-rule files, security documentation/tests.
- **Migration risk:** changing backup scope affects restore behavior, not live data.
- **Tests:** merged-manifest inspection and XML assertions for models, partials,
  secure-key preferences, caches, sessions, and ordinary settings.
- **User impact:** downloaded models will not unexpectedly reappear after restore;
  safe settings/session retention needs explicit classification.
- **Privacy/security:** provider credentials must never enter cloud backup or device
  transfer. Session source text also requires an explicit privacy decision.

### 6. Groq quota handling and queueing

- **Upstream behavior:** Qwen-specific 8K TPM, 30 RPM, and 200K TPD policy;
  organization-wide remaining/reset header synchronization; daily exhaustion
  detection; 429 countdown; bounded 5xx retries; Qwen non-thinking payload fields.
- **Focal behavior:** one configurable limiter instance uses generic 28K TPM and
  480K TPD defaults for every provider. It has no RPM window, no provider-scoped
  persistence, no server quota synchronization, and no 5xx retry loop.
- **Conflict/overlap:** useful mechanisms are mixed with Groq/Qwen product policy.
- **Disposition:** **Adapt for Focal**.
- **Affected files:** `ProviderConfig.kt`, `RateLimiter.kt`, `CloudEngine.kt`,
  `SummaryViewModel.kt`, settings/status UI, persistence, tests.
- **Migration risk:** the existing `rate_limiter` preference data is not keyed by
  provider; reinterpreting it could block or permit incorrect requests.
- **Tests:** provider isolation, TPM/RPM/TPD rolling windows, server headers,
  duration parsing, daily exhaustion, cancellation, 429, 5xx, auth errors, and
  uncertain-network retry behavior.
- **Dependency implication:** none beyond current coroutines and OkHttp.
- **User impact:** visible provider-specific waits and fewer failed requests.
- **Privacy/security:** no retry may switch providers; ambiguous network failures
  must avoid duplicate billing where a request may have completed.

### 7. Groq model retirement and Qwen payload

- **Upstream behavior:** forces `qwen/qwen3.6-27b`, overwrites stale saved model IDs,
  and sends Qwen-specific reasoning controls.
- **Focal behavior:** Groq's first/default model is the retired Scout ID, but Focal
  intentionally permits per-provider model selection and provider-aware sessions.
- **Disposition:** **Adapt for Focal** for the default/migration; **Reject
  intentionally** for globally pinning all Groq sessions without user visibility.
- **Affected files:** provider metadata, settings migration, cloud request options,
  session restoration, UI, tests.
- **Migration risk:** silently changing a restored session's model changes behavior.
- **Tests:** stale default migration, explicitly selected model preservation,
  unavailable-model error, and Qwen-only payload options.
- **User impact:** new installs avoid a retired model; existing sessions remain
  explainable and provider/model-aware.
- **Privacy/security:** provider does not change; cost/quota behavior may change and
  must be visible.

### 8. Summarization request sizing

- **Upstream behavior:** reduces cloud chunks/output budgets to fit Qwen's Groq
  free-tier window and caps the number of cloud chunks.
- **Focal behavior:** `bigContext=true` gives every cloud provider 40,000-character
  chunks, up to 40 chunks, with no provider/model capability input.
- **Disposition:** **Adapt for Focal** into provider capability/request policy.
- **Affected files:** `ProviderConfig.kt`, `SummarizeEngine.kt`, engine construction,
  session/checkpoint tests.
- **Migration risk:** changing chunk boundaries can alter resume checkpoint meaning;
  existing checkpoint lists need deterministic handling.
- **Tests:** policy-specific chunking, maximum request estimate, hierarchical combine,
  chapter/Ask bounds, checkpoint resume, and very large source coverage.
- **User impact:** slower queued runs for constrained providers, fewer 429 errors.
- **Privacy/security:** source truncation must be disclosed; no provider switch.

### 9. Model/setup/settings UI

- **Upstream behavior:** clearer model descriptions, human-readable sizes, private
  storage explanation, Groq budget status, and Qwen-specific setup copy.
- **Focal behavior:** model UI has raw MB labels; Setup still says Sonario/Groq;
  Settings advertises multi-provider BYOK but contains an unwired provider picker
  and Groq-centric state/method names.
- **Disposition:** **Adapt for Focal** for objective information and error/status
  behavior; **Reject intentionally** for Groq-only product UI and subjective restyling.
- **Affected files:** all three screens, view-model UI state, strings/resources, tests.
- **Migration risk:** none directly, but UI must expose persisted provider state.
- **Tests:** provider selection, model selection, size formatting, download errors,
  missing credentials, and session restoration.
- **User impact:** accurate choices and errors without changing visual taste.
- **Privacy/security:** disclosure must state which provider receives source text.

### 10. Documentation and release metadata

- **Upstream behavior:** documents Sonario 1.4.0/1.5.0, Groq Qwen policy, model
  choices, storage, and download behavior.
- **Focal behavior:** README describes BYOK but still uses Sonario headings/history;
  build docs and UI contain stale identity/version claims.
- **Disposition:** **Adapt for Focal**.
- **Affected files:** README, changelog, build/provider/model/migration docs, notices.
- **Migration risk:** documentation must not promise compatibility before tests.
- **Tests:** command validation, link checks where practical, and APK identity checks.
- **User impact:** accurate lineage, provider/privacy disclosure, and build guidance.
- **Privacy/security:** no secret examples or unsupported security claims.

## Explicitly preserved Focal architecture

The following repaired-only behavior supersedes upstream and must remain:

- `CloudEngine` with native Anthropic and generic OpenAI-compatible requests.
- Stable `LlmProvider` identifiers and per-provider `ProviderConfig`.
- Encrypted per-provider credential storage and legacy Groq-key migration.
- Per-provider model, base URL, and temperature persistence.
- Provider/model fields in saved sessions and restoration without fallback.
- Custom endpoints and no-key local/Ollama-compatible operation.
- Application ID `ai.focal.app`.

Upstream plaintext Groq-key storage, Groq-only engine selection, Sonario
application ID, fixed Sonario versioning, and any silent provider fallback are
**rejected intentionally**.

## Unavailable evidence and owner decisions

- The three new GGUF payloads were not downloaded or loaded on a device. URL and
  size metadata are verified, but runtime compatibility remains unverified.
- No emulator/device is currently attached, so local-model performance, memory,
  upgrade, uninstall, and backup behavior cannot yet be claimed.
- No product decision is required to begin reliability work. If device validation
  later shows that a listed model cannot run acceptably, catalog inclusion becomes
  an owner decision rather than an engineering assumption.
- Whether saved session source text should participate in cloud backup is a privacy
  product decision if repository evidence cannot establish the intended policy.

## Implementation order and gates

1. Create `integration/focal-1.0.0` from the repaired branch after this parity
   record is committed.
2. Add tests around downloader decisions and model cleanup before changing them.
3. Integrate Llamatik/catalog/downloader behavior as separate commits.
4. Wire backup rules with explicit credential exclusions and manifest tests.
5. Introduce provider-scoped request policies before adapting Groq quota headers.
6. Migrate the Groq default without rewriting explicit provider/model session state.
7. Apply factual UI/documentation updates without subjective redesign.
8. Run tests, lint, debug, and unsigned release builds after each coherent unit.

No upstream commit will be merged or cherry-picked wholesale.
