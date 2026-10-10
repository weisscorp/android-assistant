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
