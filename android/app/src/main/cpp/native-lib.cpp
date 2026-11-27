#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AMZ-AI", __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static llama_sampler * g_sampler = nullptr;

static void release_locked() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amz_ai_NativeLlama_nativeVersion(JNIEnv * env, jobject) {
    return env->NewStringUTF("llama.cpp / native CPU backend");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amz_ai_NativeLlama_nativeLoad(JNIEnv * env, jobject, jstring path_, jint contextSize) {
    const char * path = env->GetStringUTFChars(path_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
    llama_backend_init();

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // portable CPU baseline; GPU backends are a later device-specific step
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(path_, path);
    if (!g_model) return env->NewStringUTF("ERROR: Model load failed. Check file integrity and available RAM.");

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(std::clamp(contextSize, 512, 8192));
    ctx_params.n_batch = std::min<uint32_t>(ctx_params.n_ctx, 2048);
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        release_locked();
        return env->NewStringUTF("ERROR: Could not allocate inference context. Try a smaller context size.");
    }
    auto sampler_params = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    return env->NewStringUTF("OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amz_ai_NativeLlama_nativeGenerate(JNIEnv * env, jobject, jstring prompt_, jint maxTokens) {
    const char * prompt = env->GetStringUTFChars(prompt_, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx || !g_sampler) {
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("ERROR: No model is loaded.");
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t needed = -llama_tokenize(vocab, prompt, (int32_t)strlen(prompt), nullptr, 0, true, false);
    if (needed <= 0 || needed > 32768) {
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("ERROR: Prompt is empty or too large.");
    }
    std::vector<llama_token> tokens((size_t)needed);
    const int32_t count = llama_tokenize(vocab, prompt, (int32_t)strlen(prompt), tokens.data(), needed, true, false);
    env->ReleaseStringUTFChars(prompt_, prompt);
    if (count < 0) return env->NewStringUTF("ERROR: Prompt tokenization failed.");
    tokens.resize((size_t)count);

    if (llama_memory_clear(llama_get_memory(g_ctx), true), false) {}
    auto batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("ERROR: Prompt evaluation failed. Reduce context size.");

    std::string output;
    std::vector<char> piece(16 * 1024);
    const int limit = std::clamp((int)maxTokens, 1, 2048);
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        const int32_t n = llama_token_to_piece(vocab, token, piece.data(), (int32_t)piece.size(), 0, true);
        if (n > 0) output.append(piece.data(), (size_t)n);
        llama_token one = token;
        auto next = llama_batch_get_one(&one, 1);
        if (llama_decode(g_ctx, next) != 0) break;
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_amz_ai_NativeLlama_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
}
