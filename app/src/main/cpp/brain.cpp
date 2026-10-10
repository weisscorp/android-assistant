#include <jni.h>
#include "llama.h"
#include "chat.h"
#include "nlohmann/json.hpp"
#include <memory>
#include <stdexcept>
#include <atomic>
#include <array>
#include <chrono>
#include <string>
#include <vector>
#include <algorithm>
#include <thread>

// Only the system-instruction prefix survives an invocation. User/history/output KV is detached.
struct Brain {
    llama_model *model;
    llama_context *ctx = nullptr;
    std::vector<llama_token> prefix;
    std::array<long long, 7> stats{};
    common_chat_templates_ptr templates;
    std::vector<llama_token> instruction;
    int contextSize = 2048;
    std::vector<std::string> stops;
    explicit Brain(llama_model *value, int size = 2048) : model(value) {
        contextSize = std::max(512, std::min(size, llama_model_n_ctx_train(model)));
        // Never substitute ChatML for a file without a chat template.
        if (llama_model_chat_template(model, nullptr)) templates = common_chat_templates_init(model, "");
    }
    void clear() { if (ctx) llama_free(ctx); ctx = nullptr; prefix.clear(); }
    ~Brain() { clear(); llama_model_free(model); }
};
using json = nlohmann::ordered_json;
static std::atomic<long long> operation{0};
static std::vector<llama_token> tokenize(llama_model *model, const std::string &text) {
    auto *vocab = llama_model_get_vocab(model);
    int count = -llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
    if (count <= 0) throw std::runtime_error("Cannot tokenize model input");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, text.data(), text.size(), tokens.data(), count, true, true) != count)
        throw std::runtime_error("Tokenizer failed");
    return tokens;
}
static std::string render(Brain *brain, const std::vector<common_chat_msg> &messages, bool generation = true) {
    if (!brain->templates) {
        std::string result;
        for (const auto &m : messages) result += m.role + ": " + m.content + "\n";
        return result + (generation ? "assistant: " : ""); // Plain completion model, no invented special tokens.
    }
    common_chat_templates_inputs inputs;
    inputs.messages = messages; inputs.add_generation_prompt = generation;
    inputs.enable_thinking = false; inputs.use_jinja = true;
    inputs.chat_template_kwargs["enable_thinking"] = "false";
    auto rendered = common_chat_templates_apply(brain->templates.get(), inputs);
    if (generation) brain->stops = rendered.additional_stops;
    return rendered.prompt;
}
static std::string metadata(llama_model *model) {
    char arch[128]{}; llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));
    const char *tmpl = llama_model_chat_template(model, nullptr);
    char quant[128]{}; llama_model_meta_val_str(model, "general.file_type", quant, sizeof(quant));
    return json{{"architecture", arch}, {"chatTemplate", tmpl ? tmpl : ""}, {"trainedContextSize", llama_model_n_ctx_train(model)},
        {"contextSize", std::min(2048, llama_model_n_ctx_train(model))}, {"recommendedThreads", 4},
        {"fileType", quant}, {"bindingRevision", ASSISTANT_BINDING_REVISION}, {"llamaRevision", ASSISTANT_LLAMA_REVISION}}.dump();
}
struct Guard { long long id; std::chrono::steady_clock::time_point until; };
static bool abort_eval(void *data) {
    auto *guard = static_cast<Guard *>(data);
    return operation.load() != guard->id || std::chrono::steady_clock::now() > guard->until;
}
static std::string bytes(JNIEnv *env, jbyteArray data) {
    std::string value(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
static jbyteArray output(JNIEnv *env, const std::string &value) {
    auto result = env->NewByteArray(value.size());
    env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
// Stop constrained generation as soon as the root object is complete, including braces inside strings.
static bool complete_json(const std::string &text) {
    bool quoted = false, escaped = false, began = false;
    int depth = 0;
    for (char c : text) {
        if (quoted) {
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') quoted = false;
        } else if (c == '"') quoted = true;
        else if (c == '{') { began = true; ++depth; }
        else if (c == '}' && began && --depth == 0) return true;
    }
    return false;
}
static void fail(JNIEnv *env, const char *message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
extern "C" JNIEXPORT void JNICALL Java_local_voice_LocalBrain_nativeOperation(JNIEnv *, jobject, jlong id) { operation.store(id); }
extern "C" JNIEXPORT jlong JNICALL Java_local_voice_LocalBrain_nativeLoad(JNIEnv *env, jobject, jbyteArray path, jint contextSize) {
    llama_backend_init();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    auto model = llama_model_load_from_file(bytes(env, path).c_str(), params);
    if (!model) { fail(env, "Cannot load GGUF model"); return 0; }
    try { return reinterpret_cast<jlong>(new Brain(model, contextSize)); }
    catch (const std::exception &e) { llama_model_free(model); fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_local_voice_LocalBrain_nativeFree(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Brain *>(handle);
}
extern "C" JNIEXPORT jint JNICALL Java_local_voice_LocalBrain_nativePromptTokens(JNIEnv *env, jobject, jlong handle, jbyteArray prompt) {
    auto brain = reinterpret_cast<Brain *>(handle);
    if (!brain) { fail(env, "Model not loaded"); return 0; }
    auto model = brain->model;
    auto text = bytes(env, prompt);
    return -llama_tokenize(llama_model_get_vocab(model), text.data(), text.size(), nullptr, 0, true, true);
}
extern "C" JNIEXPORT void JNICALL Java_local_voice_LocalBrain_nativeDropContext(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Brain *>(handle)->clear();
}
extern "C" JNIEXPORT jlongArray JNICALL Java_local_voice_LocalBrain_nativeStats(JNIEnv *env, jobject, jlong handle) {
    auto result = env->NewLongArray(7);
    auto brain = reinterpret_cast<Brain *>(handle);
    if (brain) { std::array<jlong, 7> stats{}; std::copy(brain->stats.begin(), brain->stats.end(), stats.begin()); env->SetLongArrayRegion(result, 0, 7, stats.data()); }
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_local_voice_LocalBrain_nativeGenerate(JNIEnv *env, jobject, jlong handle, jbyteArray prompt, jlong id, jboolean structured, jboolean workflow, jint screenTask, jbyteArray allowedTools, jboolean memoryAnswer, jint threadBudget) {
    auto brain = reinterpret_cast<Brain *>(handle);
    if (!brain) { fail(env, "Model not loaded"); return nullptr; }
    auto model = brain->model;
    const auto started = std::chrono::steady_clock::now();
    const auto threads = std::min(std::max(1, std::min(4, threadBudget)), static_cast<int>(std::max(1u, std::thread::hardware_concurrency())));
    Guard guard{id, started + std::chrono::seconds(90)};
    auto vocab = llama_model_get_vocab(model);
    auto text = bytes(env, prompt);
    int count = -llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
    if (count <= 0 || count > brain->contextSize - 224) { fail(env, "Prompt exceeds local model context"); return nullptr; }
    std::vector<llama_token> tokens(count);
    llama_tokenize(vocab, text.data(), text.size(), tokens.data(), count, true, true);
    int prefixCount = 0;
    while (prefixCount < count && prefixCount < static_cast<int>(brain->instruction.size()) &&
           tokens[prefixCount] == brain->instruction[prefixCount]) ++prefixCount;
    const bool cacheable = !llama_model_is_recurrent(model) && !llama_model_has_encoder(model) && llama_model_n_swa(model) == 0;
    if (brain->ctx && !brain->prefix.empty()) {
        auto memory = llama_get_memory(brain->ctx);
        if (!cacheable || llama_memory_seq_pos_min(memory, 0) != 0 || llama_memory_seq_pos_max(memory, 0) != static_cast<int>(brain->prefix.size()) - 1) brain->clear();
    }
    int common = 0;
    while (common < prefixCount && common < static_cast<int>(brain->prefix.size()) && tokens[common] == brain->prefix[common]) ++common;
    if (brain->ctx && !llama_memory_seq_rm(llama_get_memory(brain->ctx), 0, common, -1)) { brain->clear(); common = 0; }
    if (!brain->ctx) {
        auto params = llama_context_default_params();
        params.n_ctx = brain->contextSize; params.n_batch = 128; params.n_ubatch = 128;
        params.n_threads = threads;
        params.n_threads_batch = params.n_threads;
        brain->ctx = llama_init_from_model(model, params);
        if (!brain->ctx) { fail(env, "Insufficient memory for model context"); return nullptr; }
        common = 0;
    }
    auto ctx = brain->ctx;
    // Reused contexts must adapt when a text request is followed by a voice request.
    llama_set_n_threads(ctx, threads, threads);
    llama_set_abort_callback(ctx, abort_eval, &guard);
    bool failed = false;
    for (int pos = common; pos < count && !failed; pos += 128) {
        auto batch = llama_batch_get_one(tokens.data() + pos, std::min(128, count - pos));
        failed = abort_eval(&guard) || llama_decode(ctx, batch) != 0;
    }
    const auto prefilled = std::chrono::steady_clock::now();
    auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (structured || memoryAnswer) {
        // Bounded JSON whitespace prevents small models from consuming the whole budget on spaces.
        const char *grammar = R"(root ::= "{" ws "\"tool\"" ws ":" ws tool ws "," ws "\"argument\"" ws ":" ws string ws "}"
tool ::= "\"answer\"" | "\"time\"" | "\"clock\"" | "\"battery\"" | "\"weather\"" | "\"open\"" | "\"screen\"" | "\"notifications_all\"" | "\"notification_latest\"" | "\"media\"" | "\"tap\"" | "\"write\"" | "\"scroll_down\"" | "\"scroll_up\"" | "\"navigate\"" | "\"call\"" | "\"server\""
string ::= "\"" chars "\""
chars ::= ([^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]))*
ws ::= [ \t\n\r]{0,4}
)";
        std::string selected(grammar);
        if (screenTask) {
            std::string actions;
            const auto add = [&](const char *value) { if (!actions.empty()) actions += " | "; actions += value; };
            if (screenTask & 1) add(R"("\"open\"")");
            if (screenTask & 2) add(R"("\"tap\"")");
            if (screenTask & 4) add(R"("\"write\"")");
            const auto rootEnd = selected.find('\n');
            selected.replace(0, rootEnd, actions.empty()
                ? R"(root ::= "{" ws "\"tool\"" ws ":" ws emptytool ws "," ws "\"argument\"" ws ":" ws "\"\"" ws "}")"
                : R"(root ::= "{" ws "\"tool\"" ws ":" ws (tool ws "," ws "\"argument\"" ws ":" ws string | emptytool ws "," ws "\"argument\"" ws ":" ws "\"\"") ws "}")");
            const auto begin = selected.find("tool ::=");
            const auto end = selected.find('\n', begin);
            selected.replace(begin, end - begin, "tool ::= " + (actions.empty() ? std::string(R"("\"finish\"")") : actions));
            selected += (screenTask & 8)
                ? R"(emptytool ::= "\"scroll_down\"" | "\"scroll_up\"" | "\"finish\"")"
                : R"(emptytool ::= "\"finish\"")";
        } else if (workflow) {
            selected = R"(root ::= "{" ws "\"tool\"" ws ":" ws ("\"answer\"" ws "," ws "\"argument\"" ws ":" ws "\"\"" | "\"open_then\"" ws "," ws "\"app\"" ws ":" ws string ws "," ws "\"action\"" ws ":" ws (payloadtool ws "," ws "\"argument\"" ws ":" ws string | emptytool ws "," ws "\"argument\"" ws ":" ws "\"\"")) ws "}"
payloadtool ::= "\"write\"" | "\"tap\""
emptytool ::= "\"screen\"" | "\"scroll_down\"" | "\"scroll_up\""
string ::= "\"" chars "\""
chars ::= ([^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]))*
ws ::= [ \t\n\r]{0,4}
)";
        }
        if (!screenTask && !workflow) {
            const std::vector<std::string> known = {"answer", "read_source", "time", "clock", "battery", "weather", "open", "screen", "notifications_all", "notification_latest", "media", "tap", "write", "scroll_down", "scroll_up", "navigate", "call", "server"};
            const auto allowed = "," + bytes(env, allowedTools) + ",";
            const auto quoted = [](const std::string &value) { return "\"\\\"" + value + "\\\"\""; };
            std::string choices;
            for (const auto &name : known) {
                const auto modeStart = allowed.find("," + name + "=");
                if (allowed.find("," + name + ",") == std::string::npos && modeStart == std::string::npos) continue;
                std::string argument = "string";
                if (name == "time" || name == "battery" || name == "weather" || name == "screen" || name == "scroll_down" || name == "scroll_up" || name == "notifications_all" || name == "notification_latest") argument = quoted("");
                if (name == "media") {
                    const auto begin = modeStart == std::string::npos ? 0 : modeStart + name.size() + 2;
                    const auto selectedMode = modeStart == std::string::npos ? std::string() : allowed.substr(begin, allowed.find(',', begin) - begin);
                    argument = "(";
                    for (const auto &mode : {"play", "pause", "next", "previous", "louder", "quieter", "info"}) {
                        if (!selectedMode.empty() && selectedMode != mode) continue;
                        if (argument.size() > 1) argument += " | ";
                        argument += quoted(mode);
                    }
                    if (argument.size() == 1) { llama_sampler_free(sampler); brain->clear(); fail(env, "Invalid media operation"); return nullptr; }
                    argument += ")";
                }
                if (name == "read_source") {
                    if (modeStart == std::string::npos) continue;
                    const auto begin = modeStart + name.size() + 2;
                    const auto ids = allowed.substr(begin, allowed.find(',', begin) - begin);
                    argument = "(";
                    size_t cursor = 0;
                    do {
                        const auto end = ids.find('|', cursor);
                        const auto value = ids.substr(cursor, end == std::string::npos ? end : end - cursor);
                        if (value.size() != 36 || !std::all_of(value.begin(), value.end(), [](char c) { return (c >= 'a' && c <= 'f') || (c >= '0' && c <= '9') || c == '-'; })) {
                            llama_sampler_free(sampler); brain->clear(); fail(env, "Invalid knowledge identifier"); return nullptr;
                        }
                        if (argument.size() > 1) argument += " | ";
                        argument += quoted(value);
                        if (end == std::string::npos) break;
                        cursor = end + 1;
                    } while (cursor < ids.size());
                    argument += ")";
                }
                if (name == "server") {
                    if (modeStart == std::string::npos) continue;
                    const auto begin = modeStart + name.size() + 2;
                    const auto mode = allowed.substr(begin, allowed.find(',', begin) - begin);
                    if (mode != "task" && mode != "develop" && mode != "status" && mode != "cancel") continue;
                    argument = quoted(mode);
                }
                if (!choices.empty()) choices += " | ";
                choices += quoted(name) + R"( ws "," ws "\"argument\"" ws ":" ws )" + argument;
            }
            if (choices.empty()) choices = quoted("answer") + R"( ws "," ws "\"argument\"" ws ":" ws string)";
            selected.replace(0, selected.find('\n'), R"(root ::= "{" ws "\"tool\"" ws ":" ws choice ws "}")");
            selected += "\nchoice ::= " + choices + "\n";
        }
        if (memoryAnswer) {
            selected = R"(root ::= "{" ws "\"answer\"" ws ":" ws string ws "," ws "\"topics\"" ws ":" ws array ws "," ws "\"points\"" ws ":" ws array ws "}"
array ::= "[" ws (string (ws "," ws string (ws "," ws string)?)?)? ws "]"
string ::= "\"" chars "\""
chars ::= ([^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]))*
ws ::= [ \t\n\r]{0,4}
)";
        }
        auto constrained = llama_sampler_init_grammar(vocab, selected.c_str(), "root");
        if (!constrained) { llama_sampler_free(sampler); brain->clear(); fail(env, "Invalid tool grammar"); return nullptr; }
        llama_sampler_chain_add(sampler, constrained);
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string response;
    for (int i = 0; i < (memoryAnswer ? 224 : 160) && !failed; ++i) {
        if (abort_eval(&guard)) { failed = true; break; }
        auto token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
        if (n > 0) response.append(piece.data(), n);
        if ((structured || memoryAnswer) && complete_json(response)) break;
        bool stopped = false;
        for (const auto &stop : brain->stops) {
            auto at = response.find(stop);
            if (!stop.empty() && at != std::string::npos) { response.resize(at); stopped = true; break; }
        }
        if (stopped) break;
        auto batch = llama_batch_get_one(&token, 1);
        failed = llama_decode(ctx, batch) != 0;
    }
    llama_sampler_free(sampler);
    llama_set_abort_callback(ctx, nullptr, nullptr);
    if (failed) { brain->clear(); fail(env, "Local inference cancelled, timed out or failed"); return nullptr; }
    if ((structured || memoryAnswer) && !complete_json(response)) {
        brain->clear(); fail(env, "Model exceeded the token budget before completing JSON"); return nullptr;
    }
    const auto actualThreads = llama_n_threads(ctx);
    if (cacheable && prefixCount > 0 && llama_memory_seq_rm(llama_get_memory(ctx), 0, prefixCount, -1) &&
        llama_memory_seq_pos_min(llama_get_memory(ctx), 0) == 0 && llama_memory_seq_pos_max(llama_get_memory(ctx), 0) == prefixCount - 1) {
        brain->prefix.assign(tokens.begin(), tokens.begin() + prefixCount);
    } else brain->clear();
    const auto finished = std::chrono::steady_clock::now();
    brain->stats = {count, common, count - common, static_cast<long long>(brain->prefix.size()),
        std::chrono::duration_cast<std::chrono::microseconds>(prefilled - started).count(),
        std::chrono::duration_cast<std::chrono::microseconds>(finished - started).count(), actualThreads};
    return output(env, response);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_local_voice_LocalBrain_nativeBinding(JNIEnv *env, jobject) {
    return output(env, ASSISTANT_BINDING_REVISION);
}
extern "C" JNIEXPORT jint JNICALL Java_local_voice_LocalBrain_nativePromptLimit(JNIEnv *, jobject, jlong handle) {
    auto *brain = reinterpret_cast<Brain *>(handle);
    return brain ? brain->contextSize - 224 : 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_local_voice_LocalBrain_nativeFormatPrompt(JNIEnv *env, jobject, jlong handle, jbyteArray input) {
    auto *brain = reinterpret_cast<Brain *>(handle);
    if (!brain) { fail(env, "Model not loaded"); return nullptr; }
    try {
        const auto data = json::parse(bytes(env, input));
        if (!data.is_array() || data.size() > 8) throw std::runtime_error("Invalid messages");
        std::vector<common_chat_msg> messages;
        for (const auto &item : data) messages.push_back({item.at("role").get<std::string>(), item.at("content").get<std::string>()});
        brain->instruction.clear();
        // Derive the immutable instruction prefix using the model's own template/tokenizer.
        // Two different dummy user payloads prevent retaining any real user/history token.
        if (!messages.empty() && messages[0].role == "system") {
            const auto a = tokenize(brain->model, render(brain, {messages[0], {"user", "a"}}, false));
            const auto b = tokenize(brain->model, render(brain, {messages[0], {"user", "z"}}, false));
            size_t prefix = 0;
            while (prefix < a.size() && prefix < b.size() && a[prefix] == b[prefix]) ++prefix;
            brain->instruction.assign(a.begin(), a.begin() + prefix);
        }
        return output(env, render(brain, messages));
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_local_voice_LocalBrain_nativeInspect(JNIEnv *env, jobject, jbyteArray path) {
    llama_backend_init();
    auto params = llama_model_default_params(); params.n_gpu_layers = 0; params.vocab_only = true;
    auto *model = llama_model_load_from_file(bytes(env, path).c_str(), params);
    if (!model) { fail(env, "Unsupported or invalid GGUF architecture"); return nullptr; }
    std::unique_ptr<llama_model, decltype(&llama_model_free)> guard(model, llama_model_free);
    try {
        std::unique_ptr<Brain> brain(new Brain(model)); guard.release();
        render(brain.get(), {{"system", "Ответь кратко."}, {"user", "Привет"}});
        return output(env, metadata(model));
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}
static std::array<jlong, 7> benchmark(Brain *brain, long long id, int threadBudget, int targetTokens) {
    brain->clear();
    const auto started = std::chrono::steady_clock::now();
    Guard guard{id, started + std::chrono::seconds(180)};
    auto params = llama_context_default_params();
    params.n_ctx = brain->contextSize; params.n_batch = 128; params.n_ubatch = 128;
    params.n_threads = std::max(1, std::min(threadBudget, static_cast<int>(std::max(1u, std::thread::hardware_concurrency()))));
    params.n_threads_batch = params.n_threads;
    brain->ctx = llama_init_from_model(brain->model, params);
    if (!brain->ctx) throw std::runtime_error("Insufficient memory for model context");
    auto *ctx = brain->ctx;
    llama_set_abort_callback(ctx, abort_eval, &guard);
    std::string question = "Объясни работу голосового ассистента. Ответ по-русски, минимум 150 слов. ";
    if (targetTokens > 2) for (int i = 0; i < 12; ++i) question += "Он слушает запрос, распознаёт речь, выбирает инструмент и проверяет результат. ";
    auto prompt = render(brain, {{"system", "Ты полезный помощник."}, {"user", question}});
    auto tokens = tokenize(brain->model, prompt);
    if (tokens.size() + targetTokens >= static_cast<size_t>(brain->contextSize)) throw std::runtime_error("Benchmark exceeds context");
    const auto prefillStart = std::chrono::steady_clock::now();
    for (int pos = 0; pos < static_cast<int>(tokens.size()); pos += 128) {
        auto batch = llama_batch_get_one(tokens.data() + pos, std::min(128, static_cast<int>(tokens.size()) - pos));
        if (abort_eval(&guard) || llama_decode(ctx, batch) != 0) throw std::runtime_error("Benchmark cancelled or prefill failed");
    }
    const auto prefilled = std::chrono::steady_clock::now();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    auto token = llama_sampler_sample(sampler.get(), ctx, -1);
    const auto first = std::chrono::steady_clock::now();
    int generated = 0;
    for (int i = 0; i < targetTokens; ++i) {
        if (llama_vocab_is_eog(llama_model_get_vocab(brain->model), token)) break;
        auto batch = llama_batch_get_one(&token, 1);
        if (abort_eval(&guard) || llama_decode(ctx, batch) != 0) throw std::runtime_error("Benchmark cancelled or decode failed");
        ++generated;
        token = llama_sampler_sample(sampler.get(), ctx, -1);
    }
    const auto finished = std::chrono::steady_clock::now();
    llama_set_abort_callback(ctx, nullptr, nullptr);
    const auto micros = [](auto from, auto to) { return std::chrono::duration_cast<std::chrono::microseconds>(to - from).count(); };
    std::array<jlong, 7> result = {static_cast<jlong>(tokens.size()), generated, micros(prefillStart, prefilled), micros(first, finished), micros(started, first), llama_n_threads(ctx), llama_n_ctx(ctx)};
    brain->clear();
    return result;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_local_voice_LocalBrain_nativeBenchmark(JNIEnv *env, jobject, jlong handle, jlong id, jint threads) {
    try {
        auto *brain = reinterpret_cast<Brain *>(handle);
        if (!brain) throw std::runtime_error("Model not loaded");
        auto metrics = benchmark(brain, id, threads, 32);
        auto result = env->NewLongArray(metrics.size()); env->SetLongArrayRegion(result, 0, metrics.size(), metrics.data()); return result;
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_local_voice_LocalBrain_nativeValidate(JNIEnv *env, jobject, jbyteArray path, jlong id, jint contextSize) {
    llama_backend_init();
    auto params = llama_model_default_params(); params.n_gpu_layers = 0;
    auto *model = llama_model_load_from_file(bytes(env, path).c_str(), params);
    if (!model) { fail(env, "Cannot load GGUF weights"); return nullptr; }
    std::unique_ptr<llama_model, decltype(&llama_model_free)> guard(model, llama_model_free);
    try {
        std::unique_ptr<Brain> brain(new Brain(model, contextSize)); guard.release();
        benchmark(brain.get(), id, 2, 2);
        return output(env, metadata(model));
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}
