# Bring your own key

Open **Settings → Providers**, select a provider, choose or enter its model, and
save that provider's key. Groq, OpenAI, and Anthropic require a key. Ollama and
Custom accept an optional key because self-hosted servers vary.

Keys are independent per provider. Switching providers does not copy a key,
model, or endpoint. A running request captures its selected configuration and
Focal does not silently fall back to another provider.

## Storage

Focal encrypts keys with AES-256-GCM using a key in Android Keystore. The
encrypted value is stored in private SharedPreferences; plaintext is held only
in memory while building an authorized request. Keystore hardware backing varies
by device. If encryption or persistence fails, Focal reports the failure and
does not store a Base64/plaintext fallback.

Credentials are excluded from Android cloud backup and device transfer, are not
included in saved sessions, and are redacted from user-visible failure/crash
text. Clearing app data or uninstalling deletes the stored credentials.

## Endpoint rules

- Built-in Groq, OpenAI, and Anthropic endpoints use HTTPS.
- Remote custom endpoints must use HTTPS and cannot contain URL user-info, query
  parameters, or fragments.
- Cleartext HTTP is accepted only for `localhost`, `127.0.0.1`, or `::1`, meaning
  a service on the same Android device. A computer's LAN IP over HTTP is rejected;
  expose it through a correctly configured HTTPS endpoint instead.

See [PROVIDERS.md](PROVIDERS.md) for wire formats and model persistence.
