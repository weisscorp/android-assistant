# Third-party libraries, models and integration credits

Our original Android code/docs are MIT. External sources, binding binaries, model
weights, voices, data and notices retain the licenses below. Binary/model downloads
are not Git content; preparing a build does not relicense them.

## Build and device engines

| Component / authors | Role | Terms and source |
|---|---|---|
| Coucou / Louis Raillé | Generic tween/keyframe interpolation and easing adapted to Kotlin; original Android robot artwork | [MIT notice](app/src/main/assets/licenses/Coucou-LICENSE.txt), [pinned source](https://github.com/Louis-CFM/coucou/blob/5cb2a27af352cf7319569920aac899efd42b54bc/NotchBuddy/Sources/CoucouKit/BotEngine.swift), [reuse boundaries](docs/mascot.md) |
| llama.cpp / ggml authors | GGUF inference and Jinja templates through JNI | [MIT](licenses/llama.cpp-LICENSE), [source](https://github.com/ggml-org/llama.cpp) |
| SpeexDSP / Xiph.Org and contributors | Echo cancellation/noise suppression | [BSD-3-Clause](licenses/speexdsp-COPYING), [source](https://github.com/xiph/speexdsp) |
| Vosk / Alpha Cephei and contributors | Offline ASR fallback | [Apache-2.0](https://github.com/alphacep/vosk-api/blob/master/COPYING) |
| T-one ASR / T-Software DC | Russian streaming recognition, distributed by sherpa-onnx | [Apache-2.0 notice](licenses/T-one-ASR-LICENSE.txt), [model distribution](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| sherpa-onnx / k2-fsa contributors | Recognition/synthesis Android JNI | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE) |
| ONNX Runtime / Microsoft and contributors | Included by sherpa-onnx | [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| espeak-ng and contributors | Phonemizer bundled in the synthesis dependency/data | [GPL-3.0-or-later](https://github.com/espeak-ng/espeak-ng/blob/master/COPYING) |
| JNA and contributors | Vosk native interface | [Apache-2.0 / LGPL-2.1-or-later dual terms](https://github.com/java-native-access/jna) |
| PyTorch and contributors | Optional TorchScript TTS runtime | [License](app/src/main/assets/licenses/PyTorch-LICENSE.txt), [third-party NOTICE](app/src/main/assets/licenses/PyTorch-NOTICE.txt) |
| ICU / Unicode and contributors | Russian number formatting | [Unicode/ICU license](https://github.com/unicode-org/icu/blob/main/LICENSE) |
| Tesseract4Android / adaptech-cz and contributors | Android OCR binding | [Apache-2.0](app/src/main/assets/licenses/Tesseract4Android-LICENSE.txt) |
| Tesseract / tessdata_fast contributors | OCR and Russian/English weights | [Apache-2.0](app/src/main/assets/licenses/Tesseract-data-LICENSE.txt), [model source](https://github.com/tesseract-ocr/tessdata_fast) |
| Gradle contributors | Build wrapper scripts/JAR | [Apache-2.0](https://github.com/gradle/gradle/blob/master/LICENSE) |

Native pins are llama.cpp `d81235049384534c167caea52b85a694f6103d14` and
SpeexDSP `1b28a0f61bc31162979e1f26f3981fc3637095c8`. Build versions include
sherpa-onnx 1.13.8, Vosk Android 0.3.75, JNA 5.18.1, PyTorch Android 2.1.0,
ICU4J 75.1 and Tesseract4Android 4.9.0. Exact downloadable artifacts/hashes are
in `dependencies.json`; downloaded source archives retain their own notices.

An assembled APK using espeak-ng/bundled third-party code has obligations beyond
our MIT source license. Preserve applicable copyright, license, NOTICE and
corresponding-source requirements before redistributing binaries. This source
publication does not distribute an APK or declare binary license clearance.

## Optional voices and models

- Piper Ruslan medium INT8, distributed by sherpa-onnx. Credit the
  [Ruslan corpus](https://ruslan-corpus.github.io/) and retain the
  [voice model card](https://huggingface.co/rhasspy/piper-voices/blob/main/ru/ru_RU/ruslan/medium/MODEL_CARD).
  Corpus/model terms: **CC BY-NC-SA 4.0**, noncommercial. Phonemizer data retain
  espeak-ng terms. The optional preparation command requires explicit acknowledgement.
- [Silero Models](https://github.com/snakers4/silero-models) Russian TTS v5.5 and
  its accompanying text models/resources: [CC BY-NC-SA 4.0](https://github.com/snakers4/silero-models/blob/master/LICENSE).
  We credit the upstream inference/text frontend used when developing the Android
  adapter. Its weights, developer-specific export and recordings are not published.
- [LiquidAI LFM2.5-1.2B-Instruct](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF)
  QAD-Q4_0 / Q4_K_M: LFM Open License v1.0, not MIT.
- [Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF),
  [Qwen3-1.7B quantization](https://huggingface.co/unsloth/Qwen3-1.7B-GGUF),
  [Qwen3.5-0.8B quantization](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF) and
  [Qwen3.5-2B quantization](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF): Apache-2.0;
  upstream Qwen and quantization authors retain credit.
- [Gemma-3-1B-IT quantization](https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF):
  Google Gemma terms, with ggml-org quantization credit.

GGUF metadata has exact revisions/filenames/hashes/templates in
`app/src/main/assets/model-catalog.json`. Compatibility does not grant permission
to ignore a model's terms. Arbitrary imported models have the owner's selected
model license. No voice cloning data or recordings are included.

## Remote data and companion projects

[Open-Meteo](https://open-meteo.com/en/docs) provides weather/geocoding data;
retain Open-Meteo/model and GeoNames attribution. Its hosted API terms and
[CC BY 4.0 data terms](https://open-meteo.com/en/terms) are separate from this code.

[Assistant Hub](https://github.com/weisscorp/assistant-hub) integrates the original
[Coucou by Louis Raillé](https://github.com/Louis-CFM/coucou) (MIT),
[Hermes by Nous Research](https://github.com/NousResearch/hermes-agent) (MIT),
[Agent Orchestrator](https://github.com/OrchestratorInc/agent-orchestrator) (Apache-2.0)
and [Codex](https://github.com/openai/codex) (Apache-2.0). Apart from the generic
Coucou interpolation/easing mechanics credited above, their code/assets are not
copied into Android. Coucou's **Mochi character, its appearance/character animations,
names, icons and sounds are not MIT**: see its separate
[asset license](https://github.com/Louis-CFM/coucou/blob/v0.2.3/LICENSE-ASSETS.md).
Publication/distribution with those assets requires written author permission.
This Android app uses its own robot design and state choreography, with no Mochi
assets, poses, geometry, costumes or sounds. [Implementation notes](docs/mascot.md).
