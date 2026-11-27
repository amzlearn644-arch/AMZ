#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cstring>
#include <cstdint>
#include "llama.h"

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static llama_sampler * g_sampler = nullptr;

static void release_locked() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

// Convert complete UTF-8 code points to UTF-16. Incomplete trailing bytes stay
// in pending so a multi-byte character split across llama tokens is never lost.
static std::vector<jchar> take_utf16_prefix(std::string & pending) {
    std::vector<jchar> out;
    size_t i = 0;
    while (i < pending.size()) {
        const unsigned char c = static_cast<unsigned char>(pending[i]);
        uint32_t cp = 0;
        size_t len = 0;
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
        else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += len;
    }
    pending.erase(0, i);
    return out;
}

static void send_chunk(JNIEnv * env, jobject callback, jmethodID method,
                       const std::vector<jchar> & chars, jint tokenCount) {
    if (!callback || !method || chars.empty()) return;
    jstring chunk = env->NewString(chars.data(), static_cast<jsize>(chars.size()));
    if (!chunk) return;
    env->CallVoidMethod(callback, method, chunk, tokenCount);
    env->DeleteLocalRef(chunk);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeVersion(JNIEnv * env, jobject) {
    return env->NewStringUTF("llama.cpp / arm64 CPU");
}
extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeLoad(JNIEnv * env, jobject, jstring path_, jint contextSize) {
    const char * path = env->GetStringUTFChars(path_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
    llama_backend_init();
    auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(path_, path);
    if (!g_model) return env->NewStringUTF("ERROR: Model load failed. Check GGUF file and device RAM.");
    auto cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(std::clamp(contextSize, 512, 8192));
    cp.n_batch = std::min<uint32_t>(cp.n_ctx, 2048);
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { release_locked(); return env->NewStringUTF("ERROR: Context allocation failed. Try a smaller context."); }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    return env->NewStringUTF("OK");
}
extern "C" JNIEXPORT jstring JNICALL Java_com_amz_ai_NativeLlama_nativeGenerate(JNIEnv * env, jobject, jstring prompt_, jint maxTokens, jobject callback) {
    const char * prompt = env->GetStringUTFChars(prompt_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx || !g_sampler) { env->ReleaseStringUTFChars(prompt_, prompt); return env->NewStringUTF("ERROR: No model is loaded."); }
    llama_memory_clear(llama_get_memory(g_ctx), true);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t needed = -llama_tokenize(vocab, prompt, (int32_t)strlen(prompt), nullptr, 0, true, false);
    if (needed <= 0 || needed > 32768) { env->ReleaseStringUTFChars(prompt_, prompt); return env->NewStringUTF("ERROR: Prompt is empty or too large."); }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t count = llama_tokenize(vocab, prompt, static_cast<int32_t>(strlen(prompt)), tokens.data(), needed, true, false);
    env->ReleaseStringUTFChars(prompt_, prompt);
    if (count < 0) return env->NewStringUTF("ERROR: Prompt tokenization failed.");
    tokens.resize(static_cast<size_t>(count));
    auto batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("ERROR: Prompt evaluation failed; reduce context.");

    jmethodID callbackMethod = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        if (cls) {
            callbackMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;I)V");
            env->DeleteLocalRef(cls);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    std::string output;
    std::string pendingUtf8;
    std::vector<char> piece(16 * 1024);
    jint generatedTokens = 0;
    const int limit = std::clamp(static_cast<int>(maxTokens), 1, 2048);
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        ++generatedTokens;
        const int32_t n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (n > 0) {
            output.append(piece.data(), static_cast<size_t>(n));
            pendingUtf8.append(piece.data(), static_cast<size_t>(n));
            auto chars = take_utf16_prefix(pendingUtf8);
            send_chunk(env, callback, callbackMethod, chars, generatedTokens);
        }
        llama_token nextToken = token;
        auto nextBatch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(g_ctx, nextBatch) != 0) break;
    }
    if (!pendingUtf8.empty()) {
        std::vector<jchar> replacement(1, static_cast<jchar>(0xFFFD));
        send_chunk(env, callback, callbackMethod, replacement, generatedTokens);
    }
    return env->NewStringUTF(output.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_amz_ai_NativeLlama_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex); release_locked();
}
