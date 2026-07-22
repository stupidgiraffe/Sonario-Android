# Persistence and migrations

Focal keeps compatibility identifiers when renaming them would strand installed
data. Migrations are designed to preserve unknown values and surface problems
rather than silently deleting or changing provider behavior.

| State | Current schema/key | Legacy input | Migration and failure behavior | Tests |
| --- | --- | --- | --- | --- |
| Engine | `engine=cloud/on_device` | session `GROQ`; missing schema-1 value | maps Groq to cloud; unknown session engine is reported and restored as on-device | `SessionMigrationTest` |
| Provider | `cloud_provider`; session `cloudProviderId` | missing Groq-only field | missing maps to stable `groq`; unknown nonblank ID is preserved and blocks silent fallback | `SessionMigrationTest`, provider tests |
| Model | `cloud_model_<provider>`; session `cloudModel` | `groq_model`; retired Scout default | copies legacy model if no provider model exists; retired Groq default maps to verified Qwen default; explicit other models remain | settings/session tests |
| Credentials | encrypted `key_<provider>` in `sonario_secure_keys` | plaintext `groq_api_key`; `b64:` historical entry | remove legacy only after AES-GCM persistence succeeds; on failure retain source and surface error for retry | `SecureStorageTest`, settings migration test |
| Sessions | schema 3 JSON plus source/chapter files | schemas 1–2, `groqModel` | deterministic in-memory normalization; source, checkpoints, QA, and results preserved | `SessionMigrationTest` |
| Models | exact current catalog | three allowlisted legacy filenames | retain files referenced by sessions; remove only unreferenced allowlisted payloads and their partials | `ModelCatalogTest` |
| Rate state | provider/model-scoped limiter prefs | `rate_limiter` | only the verified Groq/Qwen path migrates compatible daily usage; no cross-provider interpretation | `RateLimiterTest` |

Session metadata writes use a temporary file and rename, with a same-target write
fallback if rename fails. A corrupt/unreadable session is skipped rather than
crashing the whole history list. The store retains at most 12 session directories.

## Install/upgrade identity

Focal uses `ai.focal.app`. It is a separate app from upstream Sonario package IDs
and cannot perform an Android in-place upgrade from those apps. Repaired Focal
builds already using `ai.focal.app` can only upgrade in place when Android accepts
the version and both APKs use the same signing certificate. Debug builds and the
future production key normally differ, so a debug-to-production install may
require uninstalling and therefore losing app-private data.

There is no supported rollback from schema-3 data to older binaries. The current
migrations do not rewrite saved JSON solely on read, so keeping an app-data backup
outside Android backup is an owner/user responsibility during pre-release testing.
