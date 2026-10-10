# Build and prepare assets

## Toolchain

Use JDK 17 or newer supported by Android Gradle Plugin, SDK platform 35,
NDK `27.2.12479018` and CMake `3.22.1`. Gradle wrapper 8.11.1 and its distribution
checksum are committed. The native target is `arm64-v8a`; minSdk is 33, targetSdk 35.

Set your SDK path through Android Studio or ignored `local.properties` (`sdk.dir`).
Neither SDK files, a developer's machine paths nor signing keys belong in Git.

```sh
python3 tools/prepare_dependencies.py --group build
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The preparation script installs the exact pinned llama.cpp/SpeexDSP source archives
and two JNI binding AARs into ignored directories. `dependencies.json` records
upstream HTTPS URLs, revisions, hashes, limits and licenses. It checks cached and
new downloads, rejects unsafe archive paths/links and preserves source notices.
An existing unrecognized source directory is never overwritten. A source marker
identifies the installed pin; it is not a guarantee that local source edits were
never made. Remove/replace a checkout only after reviewing your own changes.

All other Java/Kotlin dependencies are resolved by Gradle from the configured
repositories. Their versions are pinned in the build file. Source compilation
can succeed without voice/model assets; that alone does not validate voice runtime.

## Offline recognition and OCR

```sh
python3 tools/prepare_dependencies.py --group speech
python3 tools/prepare_dependencies.py --group ocr
```

`speech` prepares the Apache-2.0 T-one Russian recognizer (`speech-tone-ru.zip`)
and Vosk small Russian fallback (`speech-ru.zip`). T-one is distributed by
sherpa-onnx and credited to T-Software DC. `ocr` downloads the pinned bytes of
Tesseract `rus` and `eng` fast models. A changed upstream file fails its checksum
rather than silently accepting new weights. Source URLs on moving branches are
therefore verified by content, not assumed stable.

## Optional Russian synthesis

The public source includes Piper and Silero adapters; their voice weights are
not published here. A reproducible Piper Ruslan test package can be prepared
separately after reviewing its terms:

```sh
python3 tools/prepare_dependencies.py --group piper --accept-noncommercial
./gradlew :app:assembleDebug
```

In the app's **Models → Voice** settings select **Ruslan** for that package. The
historic default is Silero Baya, so explicitly change that selection when building
without the Silero export. The Ruslan corpus/model terms are noncommercial
CC BY-NC-SA 4.0; phonemizer data also retains its espeak-ng terms. This source
license does not authorize commercial redistribution of those weights or make
an assembled APK MIT-only. See [notices](../THIRD_PARTY.md).

The Silero Android adapter expects an exported `voice-silero.zip` with acoustic,
accent and optional homograph TorchScript modules and text/intonation/vocabulary
resources. The original developer export and its audio regression captures are
not part of this release; a reproduction/export pipeline is future work. Do not
rename a Python model download to this archive and assume it is compatible.
For now, use the documented Piper path or provide a correctly prepared custom voice.

## Local LLM and install boundary

No GGUF is needed to compile. At runtime use the catalog or Android's document
picker for an arbitrary GGUF. Model terms apply independently. The current catalog
records validated architectures/templates for the pinned native binding; changing
CMake or JNI can invalidate its recorded fingerprint.

The debug output is `app/build/outputs/apk/debug/app-debug.apk`. This repository
does not upload or install it on an owner's phone. Installation is a separate
owner action. Use your own signing key for release builds; never commit it.
Existing version overrides retain the legacy Gradle properties `helmetVersionCode`
and `helmetVersionName` for compatibility with earlier development tooling.

## Tests

Python dependency-preparation tests use synthetic archives, no network/devices.
JVM tests cover parsers, state, audio policies and protocol validation. Full ASR/TTS
and latency tests require the corresponding model weights and an ARM64 runtime.
Instrumented audio tests may require omitted reference fixtures. Use only an
explicit disposable emulator serial for instrumented execution, and keep
microphone recordings, logs and knowledge exports outside the checkout.
