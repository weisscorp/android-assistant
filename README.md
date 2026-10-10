# Android Assistant

A Russian-first local voice/text assistant for Android, with headset activation,
on-device speech, local GGUF models and an optional connection to
[Assistant Hub](https://github.com/weisscorp/assistant-hub).

[Русский](README.ru.md) · [Build guide](docs/build.md) · [Hub connection](docs/hub.md)

## Included source

- Android system assistant entry points and headset-button lifecycle; text input
  and on-screen start/stop controls.
- Immediate PCM capture, recognition workers, endpoint detection, local synthesis,
  audio routing, echo/noise processing, and saved recording cue volume (default 10%).
- A local model catalog with download/delete/active selection, GGUF import,
  metadata-based templates and per-model benchmark storage.
- Device tools for supported media, contacts, app navigation/accessibility,
  screen OCR, weather, clocks/calendar and knowledge-source workflows.
- Saved user knowledge/aliases, share-intent ingestion, server sync and a polling inbox.
- A bounded local interaction journal (50 MB) for request/model/tool/action analysis,
  with settings to disable, export and clear it.
- A native animated assistant core with listening/thinking/speaking states.

No Google Play services are required by the included local voice pipeline. Android
permissions, lock-screen rules and headset firmware still determine which actions
are available. Recognition and generation speed must be measured on the actual
phone; emulator latency is not a phone benchmark.

This is a development release, not a promise of reliable unrestricted app control.
The code includes experimental tools and earlier release/update client modules.
The launcher/CI in this repository do not install APKs or test physical devices.
Coucou's mascot is **not yet ported** here; see [credits and reuse boundaries](THIRD_PARTY.md).

## Build source

Requirements: JDK 17+, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1.
The current native ABI is ARM64, and minSdk is Android 13 (API 33).

```sh
git clone https://github.com/weisscorp/android-assistant.git
cd android-assistant
python3 tools/prepare_dependencies.py --group build
# Configure sdk.dir in ignored local.properties, or use your SDK environment.
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

This creates a **source-validation APK without speech/OCR weights**. For an offline
voice build, prepare the assets as described in [the build guide](docs/build.md).
Weights and binary dependencies are downloaded separately, verified by SHA-256,
and ignored by Git. The optional Russian voices have separate noncommercial
terms; our source-code MIT license does not change them.

## Connect to the same Hub

Keep capture/STT/TTS and phone tool execution on the phone. The optional home
connection sends text and knowledge snapshots to the companion Hub. Configure
its authenticated HTTPS endpoint in settings; a loopback desktop URL cannot be
used directly from a phone. The current Hub requires a separately deployed TLS
gateway and does not yet return arbitrary remote phone tool calls.
[Connection details and limitations](docs/hub.md).

The two repositories can be cloned side by side. No site, person, private chat,
server address, token or family-specific recipe is bundled as application knowledge.

## Models and speech

The tracked catalog contains only metadata, not model weights. It currently includes
LiquidAI LFM2.5, Qwen3/Qwen3.5 and Gemma options with architecture/binding validation
records and an approximately 2 GB per-entry catalog cap. The exact pins, templates,
checksums and sizes are in `app/src/main/assets/model-catalog.json`.
The compiled JNI fingerprint participates in compatibility checks; update validation
when changing llama.cpp/JNI instead of just labeling an architecture supported.
[Model and speech notes](docs/models.md).

## Tests and private data

```sh
python3 -m unittest discover -s tools -p 'test_prepare_dependencies.py' -q
./gradlew :app:testDebugUnitTest
```

Instrumented tests target an explicitly selected disposable ARM64 emulator only.
Several audio/reference tests additionally need private or regenerated fixtures;
those captures are deliberately absent from this source release. Do not run an
unqualified `adb` command against attached physical devices.

The current journal defaults to enabled and can contain verbatim prompts, replies
and tool data. Review settings before using it, keep exports private, and never
commit them. No journal/history/database is published here. See [security](SECURITY.md).

## License and credits

Our code/docs are [MIT](LICENSE). [Third-party notices](THIRD_PARTY.md) cover
llama.cpp, Vosk, sherpa-onnx, SpeexDSP, PyTorch, Tesseract and optional voices/models.
Original licenses and notices are retained. The Hub integrates the original
[Coucou](https://github.com/Louis-CFM/coucou), [Hermes](https://github.com/NousResearch/hermes-agent)
and [Agent Orchestrator](https://github.com/OrchestratorInc/agent-orchestrator);
their authorship and licensing remain separate from this Android application.
