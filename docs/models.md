# Model and speech metadata

The catalog is stored in `app/src/main/assets/model-catalog.json`. It records IDs,
families, repositories, filenames, sizes, quantization, templates, context sizes,
thread recommendations, checksums and compatibility evidence. Tokenization/chat
templates come from model metadata through the pinned llama.cpp binding, not a
universal hardcoded Qwen tokenizer.

| Catalog ID | Quantization | Download bytes (rounded) |
|---|---|---|
| qwen35-2b-q4km | Q4_K_M | 1281 MB |
| lfm25-12b-qad-q4 | Q4_0 | 696 MB |
| lfm25-12b-q4km | Q4_K_M | 731 MB |
| qwen3-17b-q4km | Q4_K_M | 1107 MB |
| qwen35-08b-q4km | Q4_K_M | 533 MB |
| gemma3-1b-q4km | Q4_K_M | 806 MB |
| qwen3-06b-q8 | Q8_0 | 639 MB |
| smollm-135m-q4km | Q4_K_M | 105 MB |
| tinyllama-11b-q4km | Q4_K_M | 669 MB |

Sizes are per model; the catalog cap is approximately 2 GB per entry, not a claim
that all downloaded models together occupy under 2 GB. The picker also accepts
an arbitrary GGUF; import does not make an unsupported architecture compatible.

Catalog visibility and load validation are tied to the actual native revision
and JNI fingerprint. Adding a new architecture needs GGUF metadata/template
inspection and Android binding checks, not just an upstream family name. Prefer
updating the validation evidence when changing the native implementation.

The built-in benchmark records prompt-processing tok/s, generation tok/s,
time-to-first-token and peak RAM separately per model. Measure on the device
intended to run it. Warm/cold loading, prompt length, context budget, CPU threads,
thermal throttling and battery state affect the result. An emulator comparison
cannot establish phone latency or recommend a model solely by parameter count.

Speech engines/voice assets have their own licenses and build preparation steps;
see [build instructions](build.md) and [third-party notices](../THIRD_PARTY.md).
The public repository contains metadata and engine code, not private speech
samples, cloned voices or model weights.

## Experimental compact models

SmolLM-135M-Instruct and TinyLlama-1.1B-Chat-v1.0 use their actual GGUF `llama`
architecture and embedded chat templates. Both are optional downloads, pinned by
revision, byte count and SHA-256; adding them does not switch an existing active model.
The files are about 105 MB and 669 MB respectively. These are download sizes, not
RAM budgets. Upstream models and quantizations:

- [SmolLM](https://huggingface.co/HuggingFaceTB/SmolLM-135M-Instruct),
  [Q4_K_M artifact](https://huggingface.co/QuantFactory/SmolLM-135M-Instruct-GGUF).
- [TinyLlama](https://huggingface.co/TinyLlama/TinyLlama-1.1B-Chat-v1.0),
  [Q4_K_M artifact](https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF).

On the owned ARM64/API36 emulator, SmolLM produced valid structured JSON but selected
`answer` instead of the battery tool, and did not answer the Russian `2 + 2` prompt.
TinyLlama selected `battery`; it included `2 + 2 = 4` but kept generating an irrelevant
arithmetic sequence instead of a short answer. These observations are quality probes,
not evidence that either model is suitable as the default Russian assistant.

Initial cold application benchmark (four threads, context 2048):

| Model | Prompt tok/s | Generation tok/s | TTFT | Peak process RSS |
|---|---:|---:|---:|---:|
| SmolLM 135M | 631 | 165 | 1.34 s | 364 MiB |
| TinyLlama 1.1B | 103 | 61 | 3.86 s | 869 MiB |

These numbers describe the initial emulator run, not Redmi performance. Repeated runs
varied substantially under host load, so they should not be used as phone speed claims.
RSS covers the application
process, not only weights. Compatibility proof checks loading, chat template, structured
JSON generation, benchmark persistence, cancellation and releasing native resources;
semantic results are recorded separately by `CompactModelRuntimeTest`. Existing models
retain their stricter `CatalogRuntimeTest` requirement for a correct battery tool.
Run `tools/verify_model_catalog.py --serial emulator-... --adb /path/to/adb --model ID
--models /path/to/files --evidence verification/result.log --experimental` to reproduce
a compact-model check. Omit `--experimental` for the established catalog quality gate.
