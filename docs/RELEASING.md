# Releasing Focal

No production signing key, final tag, or GitHub Release is created automatically
by repository preparation. Those are owner-controlled operations.

## One-time owner setup

Generate the production key on a trusted offline or tightly controlled machine:

```bash
keytool -genkeypair -v -keystore focal-release.jks -alias focal \
  -keyalg RSA -keysize 4096 -validity 10000
```

Choose unique high-entropy passwords interactively. Do not paste them into issue
threads, shell history, Gradle files, or Git. Back up the keystore and recovery
information in at least two encrypted, access-controlled locations. Losing the
key prevents updates to installations signed with it; exposing it requires a
documented rotation/distribution response.

Configure these GitHub Actions secrets:

- `FOCAL_RELEASE_KEYSTORE_BASE64`: base64 of the binary keystore
- `FOCAL_RELEASE_KEY_ALIAS`
- `FOCAL_RELEASE_STORE_PASSWORD`
- `FOCAL_RELEASE_KEY_PASSWORD`

Restrict repository/environment access to release maintainers. Secret rotation
must preserve the signing key for update continuity unless the distribution
channel supports a controlled signing-key upgrade.

## Candidate verification

1. Review `docs/RELEASE_READINESS_1.0.0.md` and resolve all release blockers.
2. Re-run the clean matrix from [BUILDING.md](BUILDING.md).
3. Validate clean install and upgrade behavior on recorded arm64 devices/API levels.
4. Validate every advertised provider path with owner-controlled test credentials.
5. Validate each advertised local model or remove any unverified listing.
6. Confirm the branch commit, license/notice, version, manifest, exported components,
   checksums, and absence of secrets/generated artifacts in Git.

## Owner-gated publication

Only after approval, create an annotated `v1.0.0` tag at the exact reviewed commit
and push that tag. The tag workflow refuses a version mismatch or missing signing
input, verifies the signed APK, and uploads an Actions artifact with its checksum
and changelog. Inspect the certificate digest and checksum independently before
creating a GitHub Release or publishing to a store.

GitHub Release creation, release notes, Play Console upload, staged rollout, and
rollback decisions require separate explicit owner approval. Never reuse the
disposable test key used during engineering validation.
