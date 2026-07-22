# Security policy

## Supported version

Security fixes currently target the Focal 1.0.x line. This repository has not
yet published a production Focal release.

## Reporting a vulnerability

Use the repository's private security-advisory channel when available. If it is
not available, contact the repository owner privately. Do not put API keys,
private sources, exploit details, or production signing information in a public
issue.

Include the affected commit/version, Android version, device type, reproduction
steps, impact, and a redacted proof. Do not test against accounts or services you
do not own or have permission to use.

## Security model

- Provider keys are encrypted with AES-256-GCM using a non-exportable key in
  Android Keystore. Hardware backing depends on the device. Writes fail closed
  when Keystore encryption is unavailable.
- Credentials are not stored in session JSON and only allowlisted non-secret
  settings participate in Android backup/device transfer.
- Remote provider endpoints require HTTPS. Cleartext is limited to exact loopback
  hosts for same-device local services.
- Provider failure and crash text is bounded and credential-redacted before it is
  shown or persisted. Crash files are app-private and consumed on next launch.
- Downloaded models must match catalog byte counts and SHA-256 digests before
  atomic finalization into private app storage.
- The launcher activity is exported for launch/share intents. The foreground
  service and FileProvider are not exported; FileProvider grants are URI-scoped.
- The app does not implement certificate pinning; it relies on Android's platform
  trust configuration so provider certificate rotation remains maintainable.

## User responsibility and limitations

Cloud mode sends source text and prompts to the provider the user selected,
subject to that provider's policies. Focal does not operate a relay service.
Clipboard contents and user-created exports are controlled by Android and the
destination apps after the user explicitly copies or exports them.

The transitive PdfBox-Android stack includes Bouncy Castle 1.72 and triggers a
lint warning for a permissive trust-manager implementation inside that dependency.
Focal does not call that trust manager, but the dependency warning remains an
accepted third-party review item for 1.0.0 rather than being suppressed.
