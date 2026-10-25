# APK builds, signing and OTA pull contract

The owner decision of 24 October 2026 moves APK builds to GitHub-hosted runners.
`.github/workflows/android-apk.yml` uses Ubuntu 24.04 amd64, JDK 17, Android SDK /
build-tools 35, NDK 27.2.12479018 and CMake 3.22.1. No runner connects to the NAS,
tailnet, phone, ADB, microphone or audio. The TrueNAS OTA proxy pulls completed
artifacts from GitHub; its deployment and channel promotion belong to Assistant Hub.

## Events and trust boundary

- Every PR, including forks: assembleRelease + assembleDebug, debug/release JVM
  unit tests and lint. No repository secrets are referenced in the build job.
  Debug uses an ephemeral runner debug identity; it is not the phone's identity.
- Push to `main`, push of a `vMAJOR.MINOR.PATCH` tag, or dispatch on `main`:
  the same checks, then an isolated signing/publication job. A tag's commit must
  already be an ancestor of `main`. Dispatch on another ref cannot publish.
- Main and dispatch create dev prereleases. Version tags create stable releases.
  Protect `main`, version tags and workflow changes with repository review rules.
  Only `publish` has `contents: write`; checkouts do not persist credentials.
  Actions are pinned by full commit SHA. There is no `pull_request_target` path.

Gradle never receives signing credentials. The publish runner downloads the exact
successful build job's artifact ID, does not restore build caches or execute
Gradle, and exposes secrets only to the signing step. It aligns, signs and verifies
the APK using Android build-tools. A temporary mode-0700 directory holds the key
with mode-0600 files and is removed on exit. Passwords use `apksigner` environment
references, not command-line values; no identity is uploaded as an artifact.

## Owner provisioning

Add these **repository Actions Secrets** yourself:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the new release PKCS12 keystore, one line |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `assistant-release` |
| `ANDROID_KEY_PASSWORD` | Same password as the PKCS12 keystore |

Also set the nonsecret **Actions Variable** `ANDROID_SIGNER_SHA256` to the expected
certificate SHA-256 (64 hex characters, optional colon separators). Publication
fails if any input is missing or the signed APK differs from that fingerprint.
Do not put these values in workflow YAML, Git, PR comments or logs. No additional
PAT, GHCR credential or tailnet credential is needed by this workflow.

On 25 October 2026 the owner selected a **new release signing certificate** and
accepted one manual reinstall of `local.voice`. The release identity is PKCS12,
RSA 4096, valid for 30 years, alias `assistant-release`, subject
`CN=Assistant Release`. Its randomly generated password is shared by the keystore
and private key, as required by this PKCS12 setup.

The previous installation uses a different debug certificate. The first APK
signed with the new release key requires the owner's manual reinstall; it cannot
update that debug-signed installation in place. This task does not uninstall or
install anything on a phone. After migration, **all subsequent updates must keep
the same new release certificate**. Provision that release fingerprint in
`ANDROID_SIGNER_SHA256` and the proxy's signer allowlist before enabling ingestion;
the former MacBook debug certificate is no longer the expected identity.

Local provisioning files live outside Git in `~/.local/share/assistant-signing/`
(directory mode 0700, all files mode 0600):

| Local file | Purpose |
| --- | --- |
| `assistant-release.p12` | Private keystore; retain for future updates |
| `assistant-release.p12.base64` | Input for `ANDROID_KEYSTORE_BASE64` |
| `keystore-password.txt` | Input for `ANDROID_KEYSTORE_PASSWORD` |
| `key-alias.txt` | Input for `ANDROID_KEY_ALIAS` |
| `key-password.txt` | Input for `ANDROID_KEY_PASSWORD` |
| `signer-sha256.txt` | Lowercase 64-character certificate SHA-256 for `ANDROID_SIGNER_SHA256` |

The owner runs these commands; agents do not upload these credentials:

```sh
signing_dir="$HOME/.local/share/assistant-signing"
gh secret set ANDROID_KEYSTORE_BASE64 --repo weisscorp/android-assistant < "$signing_dir/assistant-release.p12.base64"
gh secret set ANDROID_KEYSTORE_PASSWORD --repo weisscorp/android-assistant < "$signing_dir/keystore-password.txt"
gh secret set ANDROID_KEY_ALIAS --repo weisscorp/android-assistant < "$signing_dir/key-alias.txt"
gh secret set ANDROID_KEY_PASSWORD --repo weisscorp/android-assistant < "$signing_dir/key-password.txt"
gh variable set ANDROID_SIGNER_SHA256 --repo weisscorp/android-assistant --body "$(cat "$signing_dir/signer-sha256.txt")"
```

These commands read files without echoing their secret contents. The owner makes
and safeguards the backup separately: losing this key prevents future updates
to installations signed by it. No automated backup or credential upload is part
of this workflow. Never commit the keystore, its base64 or password files.

The owner can inspect a release APK without connecting a phone:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs /private/path/new-release.apk
```

Compare `Signer #1 certificate SHA-256 digest` to the provisioned variable and
the proxy's independently provisioned signer allowlist. To inspect the certificate
in the release keystore locally, use `keytool -list -v -storetype PKCS12 -keystore /private/path/assistant-release.p12 -alias assistant-release`
and enter the password interactively. Never paste private key material into chat.
The fingerprint in downloaded JSON alone does not establish a trusted signer.

## Version authority

For this workflow, `versionCode = 1000 + GITHUB_RUN_NUMBER * 100 + GITHUB_RUN_ATTEMPT`.
The first value is 1101, above the owner's installed 109. Attempts 1–99 have
separate slots; larger attempts or Android's 2,100,000,000 limit fail closed.
PRs and failed runs leave harmless gaps. Dev versionName is `dev.<versionCode>`;
stable versionName is the version tag without `v`.

All channels share this counter. Publication is serialized and reads **all**
GitHub Release tags, including drafts: a candidate must exceed every `apk-N`
already present. A stale build finishing after a newer build fails rather than
regressing the channel. Rerunning a build allocates a new attempt/version; existing
assets are never overwritten or re-signed in place. GitHub may replace a pending
concurrency job; skipped numbers are expected.

Keep this workflow file/identity and its run counter stable. Do not run the old
Mac watcher as a second version authority. The owner must disable that publisher
as a separately managed service cutover; this task does not modify mini services.
Before enabling proxy ingestion, reconcile its retained high-water with this
counter, including unpublished legacy builds. If the counter is ever reset or
another authority advances beyond it, stop publication and explicitly migrate the
range after reconciling installed/proxy/GitHub versions. Do not delete releases
to bypass the guard. Keep `apk-N` records even when retaining fewer APK bytes.

## GitHub Release format for the proxy

Transport v1 is GitHub Releases in `weisscorp/android-assistant`, **not OCI**.
Every publication creates a new `apk-<versionCode>` tag at the exact source commit,
with `android-assistant-<versionCode>.apk` and `manifest.json`. Dev is marked
prerelease and never GitHub's `latest`; the version-tag channel is stable.
These bytes are public because the repository is public. The tailnet-only proxy
and its authenticated download API remain separate; CI does not expose the NAS.

Example producer manifest (hashes below are illustrative):

```json
{
  "schemaVersion": 1,
  "artifactKind": "android-apk",
  "packageName": "local.voice",
  "versionCode": 1101,
  "versionName": "dev.1101",
  "channel": "dev",
  "sha256": "<64 lowercase hex>",
  "sizeBytes": 123456,
  "signingCertificateSha256": "<64 lowercase hex>",
  "minSdk": 33,
  "targetSdk": 35,
  "supportedAbis": ["arm64-v8a"],
  "artifact": "android-assistant-1101.apk",
  "source": {
    "repository": "weisscorp/android-assistant",
    "commit": "<40 lowercase hex>",
    "ref": "refs/heads/main"
  },
  "build": {
    "workflow": ".github/workflows/android-apk.yml",
    "runId": "1234",
    "runAttempt": 1,
    "url": "https://github.com/weisscorp/android-assistant/actions/runs/1234"
  },
  "distribution": {
    "type": "github-release",
    "repository": "weisscorp/android-assistant",
    "tag": "apk-1101",
    "url": "https://github.com/weisscorp/android-assistant/releases/download/apk-1101/android-assistant-1101.apk"
  },
  "assets": {
    "profile": "public-asr-ocr",
    "offlineVoiceComplete": false,
    "included": ["speech-tone-ru.zip", "speech-ru.zip", "speech-gigaam-ru.zip", "silero_vad.onnx", "ocr/tessdata/rus.traineddata", "ocr/tessdata/eng.traineddata"],
    "omitted": ["voice-silero.zip", "voice-ru.zip"]
  }
}
```

This is **producer metadata**, not the phone's channel manifest. In the OTA design
§3–5 and §8, package/version/hash/signer/size/compatibility fields map directly.
The proxy must independently verify source/run success, size/hash, APK metadata,
alignment and the operator-provisioned signer before staging. It owns generation,
publishedAt, CAS promotion and authenticated artifactPath
`/ota/v1/apps/local.voice/artifacts/<sha256>.apk`. It must not forward the GitHub URL
as the phone's artifactPath or treat producer provenance as verified CI evidence.
The 24 October pull decision supersedes the design's earlier CI→publish API push.
No stable promotion, phone subscription or installation is implied by a Release.

The puller should paginate releases, reject drafts/partial releases, select its
configured channel and the greatest eligible versionCode rather than creation
time/`latest`, require both assets, and enforce its durable high-water. It should
allowlist repository, release tag and asset name, then use GitHub's asset download
protocol (including its documented storage redirects) within its own network
policy. The phone talks only to the proxy. Retries use the same verified bytes;
dev→stable promotion can reuse a retained digest without a rebuild, under the
proxy's release policy. APK byte SHA-256 is the identity, not a tag or OCI digest.

## Dependencies, missing assets and validation

Every build uses `tools/prepare_dependencies.py` for the `build`, `speech` and
`ocr` groups in `dependencies.json`. Cached and downloaded bytes are rechecked
against pinned SHA-256 values before extraction. Only download archives, Gradle
dependencies, NDK and CMake are cached; signing identities never enter caches.

Public T-one, Vosk, GigaAM v3, Silero VAD and OCR data are bundled. **Full offline
voice is not reproducible from this public repository:** the historical Silero
TorchScript export is unavailable. Optional Piper Ruslan is deliberately omitted
because it requires a separate explicit noncommercial-license acceptance. No
substitute model/export or private fixture is invented. Existing installed asset
caches are not evidence that a clean installation has a working TTS voice.

Local synthetic checks:

```sh
python3 -m unittest discover -s tools -p 'test_prepare_dependencies.py' -q
python3 -m unittest discover -s tools -p 'test_ci_release.py' -q
bash -n tools/ci_sign.sh
```

No Compose build configuration is provided: official Linux NDK/SDK host tools
require amd64, whereas the development Mac/Docker host is ARM64. Docker socket
access may also be unavailable in a worker sandbox. The first PR run on the
GitHub-hosted amd64 runner is the Android assemble/unit/lint acceptance check;
local Python checks alone do not prove an Android build. Signing/publication
requires owner provisioning and a trusted main/tag event after merge. A PR never
publishes a signed release. No emulator or physical device is needed for CI.
