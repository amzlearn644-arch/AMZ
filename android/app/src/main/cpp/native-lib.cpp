#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cstring>
#include <cstdint>
#include <atomic>
#include "llama.h"

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static std::atomic<bool> g_cancel_requested{false};


static void release_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

static std::string java_string(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static std::vector<jchar> take_utf16_prefix(std::string & pending) {
    std::vector<jchar> out;
    size_t i = 0;
    while (i < pending.size()) {
        const unsigned char c = static_cast<unsigned char>(pending[i]);
        uint32_t cp = 0; size_t len = 0;
        if (c <= 0x7F) { cp = c; len = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else { out.push_back(0xFFFD); ++i; continue; }
        if (i + len > pending.size()) break;
        bool valid = true;
        for (size_t j = 1; j < len; ++j) {
            const unsigned char cc = static_cast<unsigned char>(pending[i + j]);
            if ((cc & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!valid || (len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) ||
            (len == 4 && cp < 0x10000) || cp > 0x10FFFF ||
            (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD); ++i; continue;
        }
        if (cp <= 0xFFFF) out.push_back(static_cast<jchar>(cp));
        else { cp -= 0x10000; out.push_back(static_cast<jchar>(0xD800 + (cp >> 10))); out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF))); }
        i += len;
    }
    pending.erase(0, i);
    return out;
}

static jstring to_jstring(JNIEnv * env, const std::string & value) {
    std::string pending = value;
    auto chars = take_utf16_prefix(pending);
    if (!pending.empty()) chars.push_back(0xFFFD);
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

static void send_chunk(JNIEnv * env, jobject callback, jmethodID method, const std::vector<jchar> & chars, jint tokenCount) {
    if (!callback || !method || chars.empty()) return;
    jstring chunk = env->NewString(chars.data(), static_cast<jsize>(chars.size()));
    if (!chunk) return;
    env->CallVoidMethod(callback, method, chunk, tokenCount);
    env->DeleteLocalRef(chunk);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

extern "C" JNIEXPORT void JNICALL Java_com_amz_ai_NativeLlama_nativeBeginGeneration(JNIEnv *, jobject) {
    g_cancel_requested.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL Java_com_amz_ai_NativeLlama_nativeCancelGeneration(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_amz_ai_NativeLlama_nativeWasGenerationCancelled(JNIEnv *, jobject) {
    return g_cancel_requested.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeVersion(JNIEnv * env, jobject) {
    return env->NewStringUTF("llama.cpp / arm64 CPU");
}

extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeLoad(JNIEnv * env, jobject, jstring path_, jint contextSize) {
    const char * path = env->GetStringUTFChars(path_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked(); llama_backend_init();
    auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(path_, path);
    if (!g_model) return env->NewStringUTF("ERROR: Model load failed. Check GGUF file and device RAM.");
    auto cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(std::clamp(contextSize, 512, 8192));
    cp.n_batch = std::min<uint32_t>(cp.n_ctx, 2048);
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { release_locked(); return env->NewStringUTF("ERROR: Context allocation failed. Try a smaller context."); }
    return env->NewStringUTF("OK");
}

// Formats role/content messages with the model's GGUF chat template.
// If llama.cpp cannot apply the stored template, use a transparent plain-text fallback.
extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeFormatChat(JNIEnv * env, jobject, jobjectArray roles, jobjectArray contents) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !roles || !contents) return env->NewStringUTF("ERROR: Load a model before formatting chat.");
    const jsize count = std::min(env->GetArrayLength(roles), env->GetArrayLength(contents));
    std::vector<std::string> roleStrings, contentStrings;
    std::vector<llama_chat_message> chat;
    roleStrings.reserve(count); contentStrings.reserve(count); chat.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        roleStrings.push_back(java_string(env, role));
        contentStrings.push_back(java_string(env, content));
        if (role) env->DeleteLocalRef(role);
        if (content) env->DeleteLocalRef(content);
    }
    for (jsize i = 0; i < count; ++i) chat.push_back({roleStrings[i].c_str(), contentStrings[i].c_str()});
    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl) {
        std::vector<char> buffer(65536);
        int32_t written = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buffer.data(), static_cast<int32_t>(buffer.size()));
        if (written >= 0 && written < static_cast<int32_t>(buffer.size())) return to_jstring(env, std::string(buffer.data(), static_cast<size_t>(written)));
        if (written > 0 && written <= 1024 * 1024) {
            buffer.resize(static_cast<size_t>(written) + 1);
            written = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buffer.data(), static_cast<int32_t>(buffer.size()));
            if (written >= 0 && written < static_cast<int32_t>(buffer.size())) return to_jstring(env, std::string(buffer.data(), static_cast<size_t>(written)));
        }
    }
    std::string fallback;
    for (jsize i = 0; i < count; ++i) {
        fallback += roleStrings[i] + ": " + contentStrings[i] + "\n\n";
    }
    fallback += "Assistant:";
    return to_jstring(env, fallback);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeGenerate(JNIEnv * env, jobject, jstring prompt_, jint maxTokens, jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty, jobject callback) {
    const char * prompt = env->GetStringUTFChars(prompt_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) { env->ReleaseStringUTFChars(prompt_, prompt); return env->NewStringUTF("ERROR: No model is loaded."); }
    llama_memory_clear(llama_get_memory(g_ctx), true);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const uint32_t contextCapacity = llama_n_ctx(g_ctx);
    const int requestedLimit = std::clamp(static_cast<int>(maxTokens), 1, 2048);
    const int32_t needed = -llama_tokenize(vocab, prompt, static_cast<int32_t>(strlen(prompt)), nullptr, 0, true, false);
    if (needed <= 0 || static_cast<uint32_t>(needed) >= contextCapacity) {
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("ERROR: Prompt exceeds the model context. Reduce chat history or increase context length.");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t count = llama_tokenize(vocab, prompt, static_cast<int32_t>(strlen(prompt)), tokens.data(), needed, true, false);
    env->ReleaseStringUTFChars(prompt_, prompt);
    if (count < 0) return env->NewStringUTF("ERROR: Prompt tokenization failed.");
    if (static_cast<uint32_t>(count) >= contextCapacity) return env->NewStringUTF("ERROR: No context space remains for generation.");
    tokens.resize(static_cast<size_t>(count));
    auto batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("ERROR: Prompt evaluation failed; reduce context.");
    auto samplerParams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(samplerParams);
    if (!sampler) return env->NewStringUTF("ERROR: Sampler initialization failed.");
    if (repeatPenalty > 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeatPenalty, 0.0f, 0.0f));
    }
    if (topK > 0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(std::clamp(static_cast<int>(topK), 1, 1000)));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(std::clamp(topP, 0.05f, 1.0f), 1));
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::clamp(temperature, 0.05f, 2.0f)));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    jmethodID callbackMethod = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        if (cls) { callbackMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;I)V"); env->DeleteLocalRef(cls); }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    std::string output, pendingUtf8;
    std::vector<char> piece(16 * 1024);
    jint generatedTokens = 0;
    // Never decode beyond the allocated KV-cache/context capacity.
    const int availableTokens = static_cast<int>(contextCapacity - static_cast<uint32_t>(count));
    const int limit = std::min(requestedLimit, availableTokens);
    for (int i = 0; i < limit && !g_cancel_requested.load(std::memory_order_relaxed); ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        ++generatedTokens;
        const int32_t n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (n > 0) {
            output.append(piece.data(), static_cast<size_t>(n));
            pendingUtf8.append(piece.data(), static_cast<size_t>(n));
            send_chunk(env, callback, callbackMethod, take_utf16_prefix(pendingUtf8), generatedTokens);
        }
        llama_token nextToken = token;
        auto nextBatch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(g_ctx, nextBatch) != 0) break;
    }
    if (!pendingUtf8.empty()) {
        output += "\xEF\xBF\xBD";
        std::vector<jchar> replacement(1, static_cast<jchar>(0xFFFD));
        send_chunk(env, callback, callbackMethod, replacement, generatedTokens);
    }
    llama_sampler_free(sampler);
    return to_jstring(env, output);
}

extern "C" JNIEXPORT void JNICALL Java_com_amz_ai_NativeLlama_nativeUnload(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
}
