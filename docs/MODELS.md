# On-device models

Focal 1.0.0 lists three arm64 local models. Catalog metadata is exact rather than
an approximate acceptance threshold.

| Model | File name | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Qwen3 4B Instruct 2507 | `qwen3-4b-instruct-2507-q4_k_m.gguf` | 2,497,280,736 | `2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e` |
| Gemma 3n E4B Instruct | `gemma-3n-e4b-it-q4_k_m.gguf` | 4,237,063,776 | `daadf6be5e99d162226c0aaacbe5e45424244dd73ca864dfac0ad802165809e1` |
| LFM2 2.6B | `lfm2-2.6b-q4_k_m.gguf` | 1,563,668,704 | `384bc877b6c37064982f96885bef69e4475919f5969218ed4e3b9399ae0340df` |

Models are downloaded from their HTTPS Hugging Face repositories into private
app storage. The downloader reserves the remaining bytes plus 256 MB, validates
range responses, restarts safely when a server ignores Range, rejects oversized
writes, syncs the partial file, verifies exact size and SHA-256, then finalizes
with an atomic move where the filesystem supports it.

Interrupted validated partials remain resumable. Full-length corrupt partials and
new downloads with digest mismatches are removed. Duplicate concurrent downloads
for the same target are rejected.

Three older filenames remain resolvable only for durable sessions. Startup cleanup
removes their `.part` files and removes completed legacy files only when no saved
session references them; unrelated files are never scanned for deletion.

The APK contains no GGUF payload. Uninstalling or clearing app data removes models.
The catalog, URLs, sizes, hashes, and downloader decisions have automated tests;
actual loading, memory pressure, heat, speed, and output quality for all three
models have not yet been verified on physical hardware.
